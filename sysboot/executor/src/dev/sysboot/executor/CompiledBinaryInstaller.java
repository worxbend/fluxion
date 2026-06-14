package dev.sysboot.executor;

import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CompiledBinaryInstaller {

  private static final Logger log = LoggerFactory.getLogger(CompiledBinaryInstaller.class);
  private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(10);

  private final ShellRunner shellRunner;
  private final HttpClient httpClient;

  public CompiledBinaryInstaller(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
  }

  public StepResult install(CompiledBinaryModule module) {
    Instant start = Instant.now();
    Path tempFile = null;
    try {
      tempFile = Files.createTempFile("sysboot-", "-" + module.binaryName());
      Path downloadedFile = tempFile;
      download(module.url().value(), downloadedFile);
      if (module.checksum().isPresent()) {
        verifyChecksum(downloadedFile, module.checksum().orElseThrow());
      } else {
        log.warn("Installing downloaded binary '{}' without checksum verification", module.name());
      }
      extractOrCopy(downloadedFile, module);
      return new StepResult.Success(module.binaryName(), Duration.between(start, Instant.now()));
    } catch (IOException | ShellExecutionException e) {
      return new StepResult.Failure(
          module.binaryName(), e.getMessage(), 1, Duration.between(start, Instant.now()));
    } finally {
      deleteTempFile(tempFile);
    }
  }

  private void download(URI url, Path destination) throws IOException {
    log.debug("Downloading {}", url);
    HttpRequest request = HttpRequest.newBuilder(url).timeout(DOWNLOAD_TIMEOUT).GET().build();
    try {
      HttpResponse<Path> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
      if (response.statusCode() != 200) {
        throw new IOException("Download failed with HTTP " + response.statusCode() + " for " + url);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Download interrupted", e);
    }
  }

  private void verifyChecksum(Path file, Checksum checksum) {
    try {
      MessageDigest digest = MessageDigest.getInstance(checksum.algorithm());
      byte[] fileBytes = Files.readAllBytes(file);
      byte[] hash = digest.digest(fileBytes);
      String actual = HexFormat.of().formatHex(hash);
      if (!actual.equals(checksum.value())) {
        throw new ShellExecutionException(
            "Checksum mismatch: expected " + checksum.value() + " but got " + actual);
      }
    } catch (NoSuchAlgorithmException e) {
      throw new ShellExecutionException("Unknown checksum algorithm: " + checksum.algorithm(), e);
    } catch (IOException e) {
      throw new ShellExecutionException("Failed to read file for checksum verification", e);
    }
  }

  private void extractOrCopy(Path sourceFile, CompiledBinaryModule module) throws IOException {
    String urlString = module.url().value().toString();
    if (urlString.endsWith(".tar.gz") || urlString.endsWith(".tgz")) {
      extractTarGz(sourceFile, module.installPath(), module.binaryName());
    } else {
      copyBinary(sourceFile, module.installPath());
    }
  }

  private void extractTarGz(Path archive, Path installPath, String binaryName) throws IOException {
    try (InputStream fileIn = Files.newInputStream(archive);
        BufferedInputStream buffered = new BufferedInputStream(fileIn);
        GzipCompressorInputStream gzip = new GzipCompressorInputStream(buffered);
        ArchiveInputStream<? extends ArchiveEntry> tar = new TarArchiveInputStream(gzip)) {

      ArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!entry.isDirectory() && entry.getName().endsWith(binaryName)) {
          copyBinaryStream(tar, installPath);
          return;
        }
      }
    }
    throw new IOException("Binary '" + binaryName + "' not found in archive");
  }

  private void copyBinary(Path source, Path destination) throws IOException {
    if (destination.getParent() != null && isRootOwned(destination.getParent())) {
      shellRunner.run(
          List.of("sudo", "cp", source.toString(), destination.toString()),
          Map.of(),
          Duration.ofMinutes(1));
    } else {
      Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private void copyBinaryStream(InputStream input, Path destination) throws IOException {
    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
  }

  private boolean isRootOwned(Path path) {
    try {
      return "root".equals(Files.getOwner(path).getName());
    } catch (IOException e) {
      return false;
    }
  }

  private void deleteTempFile(Path tempFile) {
    if (tempFile != null) {
      try {
        Files.deleteIfExists(tempFile);
      } catch (IOException e) {
        log.warn("Failed to delete temp file: {}", tempFile);
      }
    }
  }
}
