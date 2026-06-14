package dev.sysboot.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

public final class NerdFontExecutor {

  private static final Duration FONT_INSTALL_TIMEOUT = Duration.ofMinutes(15);
  private static final String ITEM = "nerd-fonts";

  private final ShellRunner shellRunner;
  private final ObjectMapper yamlMapper;
  private final InstallerResolver installerResolver;

  public NerdFontExecutor(ShellRunner shellRunner) {
    this(shellRunner, new DownloadingInstallerResolver());
  }

  NerdFontExecutor(ShellRunner shellRunner, InstallerResolver installerResolver) {
    this.shellRunner = shellRunner;
    this.yamlMapper = new ObjectMapper(new YAMLFactory());
    this.installerResolver = installerResolver;
  }

  public StepResult execute(NerdFontModule module) {
    Path tempConfig = null;
    Path installer = null;
    try {
      tempConfig = writeTempConfig(module);
      installer = installerResolver.resolve(module);
      var command = List.of(installer.toString(), "--config", tempConfig.toString());
      var result = shellRunner.run(command, Map.of(), FONT_INSTALL_TIMEOUT);

      return result.exitCode() == 0
          ? new StepResult.Success(ITEM, result.elapsed())
          : new StepResult.Failure(ITEM, result.stderr(), result.exitCode(), result.elapsed());
    } catch (IOException e) {
      return new StepResult.Failure(
          ITEM, "Failed to prepare Nerd Font installer: " + e.getMessage(), 1, Duration.ZERO);
    } finally {
      deleteIfExists(tempConfig);
      deleteIfExists(installer);
    }
  }

  private Path writeTempConfig(NerdFontModule module) throws IOException {
    var cfg = module.config();
    var dto =
        Map.of(
            "release", cfg.release(),
            "destination", cfg.destination().toString(),
            "refresh_font_cache", cfg.refreshFontCache(),
            "families", cfg.families());
    Path tmp = Files.createTempFile("sysboot-nerdfonts-", ".yaml");
    yamlMapper.writeValue(tmp.toFile(), dto);
    return tmp;
  }

  private void deleteIfExists(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
    }
  }

  @FunctionalInterface
  interface InstallerResolver {
    Path resolve(NerdFontModule module) throws IOException;
  }

  private static final class DownloadingInstallerResolver implements InstallerResolver {

    private static final String URL_TEMPLATE =
        "https://github.com/worxbend/nerd-font-installer/releases/download/%s/nerdfont-install_%s_linux_amd64.tar.gz";

    @Override
    public Path resolve(NerdFontModule module) throws IOException {
      Path archive = Files.createTempFile("sysboot-nerdfont-installer-", ".tar.gz");
      try {
        download(installerUrl(module.installerVersion()), archive);
        return extractInstaller(archive, module.nerdfontBinary());
      } finally {
        Files.deleteIfExists(archive);
      }
    }

    private void download(URI url, Path destination) throws IOException {
      var request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(30)).GET().build();
      try {
        var client = HttpClient.newHttpClient();
        var response = client.send(request, HttpResponse.BodyHandlers.ofFile(destination));
        if (response.statusCode() != 200) {
          throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("Download interrupted", e);
      }
    }

    private URI installerUrl(String version) {
      return URI.create(URL_TEMPLATE.formatted(version, version));
    }

    private Path extractInstaller(Path archive, String binaryName) throws IOException {
      Path installer = Files.createTempFile("sysboot-nerdfont-install-", "");
      try (var input = new BufferedInputStream(Files.newInputStream(archive));
          var gzip = new GzipCompressorInputStream(input);
          ArchiveInputStream<? extends ArchiveEntry> tar = new TarArchiveInputStream(gzip)) {
        copyInstaller(tar, installer, binaryName);
      }
      Files.setPosixFilePermissions(installer, PosixFilePermissions.fromString("rwx------"));
      return installer;
    }

    private void copyInstaller(
        ArchiveInputStream<? extends ArchiveEntry> tar, Path installer, String binaryName)
        throws IOException {
      ArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!entry.isDirectory() && entry.getName().endsWith("/" + binaryName)) {
          Files.copy(tar, installer, StandardCopyOption.REPLACE_EXISTING);
          return;
        }
      }
      throw new IOException("Installer binary '" + binaryName + "' not found in archive");
    }
  }
}
