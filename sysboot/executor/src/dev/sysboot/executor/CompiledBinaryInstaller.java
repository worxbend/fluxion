package dev.sysboot.executor;

import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CompiledBinaryInstaller {

  private static final Logger log = LoggerFactory.getLogger(CompiledBinaryInstaller.class);

  private final ShellRunner shellRunner;
  private final BinaryDownloadClient downloadClient;
  private final BinaryFileSystem fileSystem;
  private final ChecksumResolver checksumResolver;
  private final DetachedSignatureVerifier signatureVerifier;

  public CompiledBinaryInstaller(ShellRunner shellRunner) {
    this(shellRunner, new HttpBinaryDownloadClient(), new DefaultBinaryFileSystem());
  }

  CompiledBinaryInstaller(ShellRunner shellRunner, ChecksumResolver checksumResolver) {
    this(
        shellRunner,
        new HttpBinaryDownloadClient(),
        new DefaultBinaryFileSystem(),
        checksumResolver);
  }

  CompiledBinaryInstaller(
      ShellRunner shellRunner, BinaryDownloadClient downloadClient, BinaryFileSystem fileSystem) {
    this(shellRunner, downloadClient, fileSystem, new ChecksumResolver(downloadClient));
  }

  private CompiledBinaryInstaller(
      ShellRunner shellRunner,
      BinaryDownloadClient downloadClient,
      BinaryFileSystem fileSystem,
      ChecksumResolver checksumResolver) {
    this.shellRunner = shellRunner;
    this.downloadClient = downloadClient;
    this.fileSystem = fileSystem;
    this.checksumResolver = checksumResolver;
    this.signatureVerifier = new DetachedSignatureVerifier(shellRunner);
  }

  public StepResult install(CompiledBinaryModule module) {
    Instant start = Instant.now();
    Path tempFile = null;
    Path extractedFile = null;
    Optional<Path> signatureFile = Optional.empty();
    try {
      tempFile = fileSystem.createTempFile("sysboot-", "-" + module.binaryName());
      Path downloadedFile = tempFile;
      downloadClient.downloadToFile(module.url().value(), downloadedFile);
      signatureFile = verifyDetachedSignature(module, downloadedFile);
      Optional<Checksum> checksum = verifyResolvedChecksum(module, downloadedFile);
      extractedFile = extractArchive(downloadedFile, module).orElse(null);
      installBinary(extractedFile != null ? extractedFile : downloadedFile, module);
      return success(module, start, checksum);
    } catch (IOException | ShellExecutionException e) {
      return new StepResult.Failure(
          module.binaryName(), e.getMessage(), 1, Duration.between(start, Instant.now()));
    } finally {
      deleteTempFile(tempFile);
      deleteTempFile(extractedFile);
      signatureFile.ifPresent(this::deleteTempFile);
    }
  }

  public List<String> dryRunCommand(CompiledBinaryModule module) {
    var command = new ArrayList<String>();
    command.addAll(
        List.of("download", module.url().toString(), "->", module.installPath().toString()));
    appendArchivePreview(module, command);
    module.installMode().ifPresent(mode -> command.addAll(List.of("mode", mode)));
    module.symlinkPath()
        .ifPresent(
            link ->
                command.addAll(
                    List.of("symlink", link.toString(), "->", module.installPath().toString())));
    return List.copyOf(command);
  }

  private Optional<Checksum> verifyResolvedChecksum(CompiledBinaryModule module, Path downloadedFile)
      throws IOException {
    Optional<Checksum> checksum = checksumResolver.resolve(module);
    if (checksum.isPresent()) {
      verifyChecksum(downloadedFile, checksum.orElseThrow());
    } else if (module.signatureUrl().isEmpty()) {
      log.warn("Installing downloaded binary '{}' without checksum verification", module.name());
    }
    return checksum;
  }

  private StepResult.Success success(
      CompiledBinaryModule module, Instant start, Optional<Checksum> checksum) {
    return new StepResult.Success(
        module.binaryName(),
        Duration.between(start, Instant.now()),
        detectedVersion(module),
        checksum.map(Checksum::value));
  }

  private Optional<Path> verifyDetachedSignature(CompiledBinaryModule module, Path downloadedFile)
      throws IOException {
    if (module.signatureUrl().isEmpty()) {
      return Optional.empty();
    }
    Path signatureFile = fileSystem.createTempFile("sysboot-", ".sig");
    downloadClient.downloadToFile(module.signatureUrl().orElseThrow().value(), signatureFile);
    signatureVerifier.verify(signatureFile, downloadedFile);
    return Optional.of(signatureFile);
  }

  private void verifyChecksum(Path file, Checksum checksum) {
    try {
      MessageDigest digest = MessageDigest.getInstance(checksum.algorithm());
      byte[] fileBytes = fileSystem.readAllBytes(file);
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

  private void appendArchivePreview(CompiledBinaryModule module, List<String> command) {
    if (!isTarGz(module)) {
      command.add("direct-binary");
      return;
    }
    command.addAll(
        List.of(
            "extract",
            module.archivePath().orElse(module.binaryName()),
            "strip-components",
            Integer.toString(module.stripComponents())));
  }

  private Optional<String> detectedVersion(CompiledBinaryModule module) {
    InstallationStatus status =
        new CompiledBinaryProbe(module.versionCommand(), module.expectedVersion())
            .probe(module.installPath().toString());
    if (status instanceof InstallationStatus.InstalledByProbe installed) {
      return Optional.ofNullable(installed.detectedVersion());
    }
    return Optional.empty();
  }

  private Optional<Path> extractArchive(Path downloadedFile, CompiledBinaryModule module)
      throws IOException {
    if (!isTarGz(module)) {
      return Optional.empty();
    }
    return Optional.of(extractTarGz(downloadedFile, module));
  }

  private Path extractTarGz(Path archive, CompiledBinaryModule module) throws IOException {
    try (InputStream fileIn = fileSystem.openInput(archive);
        BufferedInputStream buffered = new BufferedInputStream(fileIn);
        GzipCompressorInputStream gzip = new GzipCompressorInputStream(buffered);
        ArchiveInputStream<? extends ArchiveEntry> tar = new TarArchiveInputStream(gzip)) {

      ArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (!entry.isDirectory() && archiveEntryMatches(entry.getName(), module)) {
          Path extracted =
              fileSystem.createTempFile("sysboot-extracted-", "-" + module.binaryName());
          fileSystem.copy(tar, extracted);
          return extracted;
        }
      }
    }
    throw new IOException("Binary '" + module.binaryName() + "' not found in archive");
  }

  private boolean archiveEntryMatches(String entryName, CompiledBinaryModule module) {
    String stripped = stripComponents(entryName, module.stripComponents());
    if (stripped.isBlank()) {
      return false;
    }
    if (module.archivePath().isPresent()) {
      String selected = module.archivePath().orElseThrow();
      return entryName.equals(selected) || stripped.equals(selected);
    }
    return Path.of(stripped).getFileName().toString().equals(module.binaryName());
  }

  private String stripComponents(String entryName, int count) {
    String[] components = entryName.split("/");
    if (components.length <= count) {
      return "";
    }
    return String.join("/", Arrays.copyOfRange(components, count, components.length));
  }

  private void installBinary(Path source, CompiledBinaryModule module) throws IOException {
    copyBinary(source, module.installPath());
    applyMode(module.installPath(), module.installMode());
    if (module.symlinkPath().isPresent()) {
      createSymlink(module.symlinkPath().orElseThrow(), module.installPath());
    }
  }

  private void copyBinary(Path source, Path destination) throws IOException {
    if (requiresSudo(destination)) {
      runSudo(List.of("sudo", "cp", source.toString(), destination.toString()));
    } else {
      fileSystem.copy(source, destination);
    }
  }

  private void applyMode(Path destination, Optional<String> mode) throws IOException {
    if (mode.isEmpty()) {
      return;
    }
    if (requiresSudo(destination)) {
      runSudo(List.of("sudo", "chmod", mode.orElseThrow(), destination.toString()));
    } else {
      fileSystem.setMode(destination, mode.orElseThrow());
    }
  }

  private void createSymlink(Path link, Path target) throws IOException {
    if (requiresSudo(link)) {
      runSudo(List.of("sudo", "ln", "-sfn", target.toString(), link.toString()));
    } else {
      fileSystem.createSymlink(link, target);
    }
  }

  private boolean requiresSudo(Path path) {
    return path.getParent() != null && fileSystem.isRootOwned(path.getParent());
  }

  private void runSudo(List<String> command) throws IOException {
    var result = shellRunner.run(command, Map.of(), Duration.ofMinutes(1));
    if (result.exitCode() != 0) {
      throw new IOException("Command failed: " + String.join(" ", command));
    }
  }

  private boolean isTarGz(CompiledBinaryModule module) {
    String urlString = module.url().value().getPath().toLowerCase(Locale.ROOT);
    return urlString.endsWith(".tar.gz") || urlString.endsWith(".tgz");
  }

  private void deleteTempFile(Path tempFile) {
    if (tempFile != null) {
      try {
        fileSystem.deleteIfExists(tempFile);
      } catch (IOException e) {
        log.warn("Failed to delete temp file: {}", tempFile);
      }
    }
  }
}
