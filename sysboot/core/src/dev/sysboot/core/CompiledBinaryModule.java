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
    Optional<String> expectedVersion)
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
    if (binaryName.isBlank()) {
      throw new IllegalArgumentException("Binary name must not be blank");
    }
    if (stripComponents < 0) {
      throw new IllegalArgumentException("Strip components must not be negative");
    }
    archivePath =
        archivePath.map(
            value -> {
              if (value.isBlank()) {
                throw new IllegalArgumentException("Archive path must not be blank");
              }
              return value;
            });
    installMode =
        installMode.map(
            value -> {
              if (!value.matches("[0-7]{3,4}")) {
                throw new IllegalArgumentException("Install mode must be octal");
              }
              return value;
            });
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
        Optional.empty());
  }

}
