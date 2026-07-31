package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record CompiledBinaryModule(
    ModuleName name,
    String binaryName,
    BinaryUrl url,
    Optional<Checksum> checksum,
    Optional<BinaryUrl> checksumUrl,
    Optional<BinaryUrl> signatureUrl,
    Path installPath,
    Optional<String> archivePath,
    int stripComponents,
    Optional<String> installMode,
    Optional<Path> symlinkPath,
    boolean continueOnError,
    Optional<String> versionCommand,
    Optional<String> expectedVersion,
    Optional<String> allowedSignerFingerprint)
    implements BootstrapModule {

  public CompiledBinaryModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(binaryName);
    Objects.requireNonNull(url);
    Objects.requireNonNull(checksum);
    Objects.requireNonNull(checksumUrl);
    Objects.requireNonNull(signatureUrl);
    Objects.requireNonNull(installPath);
    Objects.requireNonNull(archivePath);
    Objects.requireNonNull(installMode);
    Objects.requireNonNull(symlinkPath);
    Objects.requireNonNull(versionCommand);
    Objects.requireNonNull(expectedVersion);
    Objects.requireNonNull(allowedSignerFingerprint);
    CompiledBinaryConstraints.requireFileName(binaryName);
    CompiledBinaryConstraints.requireAbsoluteNormalized(installPath, "Install path");
    symlinkPath.ifPresent(
        path -> CompiledBinaryConstraints.requireAbsoluteNormalized(path, "Symlink path"));
    if (symlinkPath.filter(installPath::equals).isPresent()) {
      throw new IllegalArgumentException("Symlink path must differ from install path");
    }
    if (symlinkPath
        .filter(path -> path.startsWith(installPath) || installPath.startsWith(path))
        .isPresent()) {
      throw new IllegalArgumentException(
          "Install path and symlink path must not contain one another");
    }
    if (stripComponents < 0) {
      throw new IllegalArgumentException("Strip components must not be negative");
    }
    archivePath = archivePath.map(CompiledBinaryConstraints::requireArchivePath);
    if (CompiledBinaryConstraints.isArchive(url) && archivePath.isEmpty()) {
      throw new IllegalArgumentException("Archive downloads must declare archivePath");
    }
    installMode =
        installMode.map(
            value -> {
              if (!value.matches("[0-7]{3,4}")) {
                throw new IllegalArgumentException("Install mode must be octal");
              }
              return value;
            });
    allowedSignerFingerprint =
        allowedSignerFingerprint.map(CompiledBinaryConstraints::normalizeSignerFingerprint);
  }

  public CompiledBinaryModule(
      ModuleName name,
      String binaryName,
      BinaryUrl url,
      Optional<Checksum> checksum,
      Optional<BinaryUrl> checksumUrl,
      Optional<BinaryUrl> signatureUrl,
      Path installPath,
      Optional<String> archivePath,
      int stripComponents,
      Optional<String> installMode,
      Optional<Path> symlinkPath,
      boolean continueOnError,
      Optional<String> versionCommand,
      Optional<String> expectedVersion) {
    this(
        name,
        binaryName,
        url,
        checksum,
        checksumUrl,
        signatureUrl,
        installPath,
        archivePath,
        stripComponents,
        installMode,
        symlinkPath,
        continueOnError,
        versionCommand,
        expectedVersion,
        Optional.empty());
  }

  public CompiledBinaryModule(
      ModuleName name,
      String binaryName,
      BinaryUrl url,
      Optional<Checksum> checksum,
      Path installPath,
      boolean continueOnError) {
    this(
        name,
        binaryName,
        url,
        checksum,
        Optional.empty(),
        Optional.empty(),
        installPath,
        Optional.empty(),
        0,
        Optional.of("0755"),
        Optional.empty(),
        continueOnError,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public CompiledBinaryModule(
      ModuleName name,
      String binaryName,
      BinaryUrl url,
      Optional<Checksum> checksum,
      Optional<BinaryUrl> checksumUrl,
      Path installPath,
      boolean continueOnError) {
    this(
        name,
        binaryName,
        url,
        checksum,
        checksumUrl,
        Optional.empty(),
        installPath,
        Optional.empty(),
        0,
        Optional.of("0755"),
        Optional.empty(),
        continueOnError,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  public CompiledBinaryModule(
      ModuleName name,
      String binaryName,
      BinaryUrl url,
      Optional<Checksum> checksum,
      Optional<BinaryUrl> checksumUrl,
      Optional<BinaryUrl> signatureUrl,
      Path installPath,
      boolean continueOnError) {
    this(
        name,
        binaryName,
        url,
        checksum,
        checksumUrl,
        signatureUrl,
        installPath,
        Optional.empty(),
        0,
        Optional.of("0755"),
        Optional.empty(),
        continueOnError,
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }
}
