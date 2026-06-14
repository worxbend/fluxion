package dev.sysboot.executor;

import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

public final class DotbotExecutor {

  private static final Duration DOTBOT_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;
  private final InstallerResolver installerResolver;

  public DotbotExecutor(ShellRunner shellRunner) {
    this(shellRunner, new DownloadingInstallerResolver());
  }

  DotbotExecutor(ShellRunner shellRunner, InstallerResolver installerResolver) {
    this.shellRunner = shellRunner;
    this.installerResolver = installerResolver;
  }

  public StepResult execute(DotbotModule module) {
    Path installer = null;
    try {
      installer = installerResolver.resolve(module);
      var command = List.of(installer.toString(), "--config", module.config().toString());
      var result = shellRunner.run(command, Map.of(), DOTBOT_TIMEOUT);

      return result.exitCode() == 0
          ? new StepResult.Success(module.name().value(), result.elapsed())
          : new StepResult.Failure(
              module.name().value(),
              "dotbot exited with code " + result.exitCode() + ": " + result.stderr(),
              result.exitCode(),
              result.elapsed());
    } catch (IOException e) {
      return new StepResult.Failure(
          module.name().value(), "Failed to prepare dotbot: " + e.getMessage(), 1, Duration.ZERO);
    } finally {
      deleteIfExists(installer);
    }
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
    Path resolve(DotbotModule module) throws IOException;
  }

  private static final class DownloadingInstallerResolver implements InstallerResolver {

    private static final String URL_TEMPLATE =
        "https://github.com/worxbend/dotbot-go/releases/download/%s/dotbot-linux-amd64.tar.gz";

    @Override
    public Path resolve(DotbotModule module) throws IOException {
      Path archive = Files.createTempFile("sysboot-dotbot-", ".tar.gz");
      try {
        download(installerUrl(module.installerVersion()), archive);
        return extractInstaller(archive, module.dotbotBinary());
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
      return URI.create(URL_TEMPLATE.formatted(version));
    }

    private Path extractInstaller(Path archive, String binaryName) throws IOException {
      Path installer = Files.createTempFile("sysboot-dotbot-", "");
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
        if (isInstallerEntry(entry, binaryName)) {
          Files.copy(tar, installer, StandardCopyOption.REPLACE_EXISTING);
          return;
        }
      }
      throw new IOException("Dotbot binary '" + binaryName + "' not found in archive");
    }

    private boolean isInstallerEntry(ArchiveEntry entry, String binaryName) {
      String entryName = entry.getName();
      return !entry.isDirectory()
          && (entryName.equals(binaryName) || entryName.endsWith("/" + binaryName));
    }
  }
}
