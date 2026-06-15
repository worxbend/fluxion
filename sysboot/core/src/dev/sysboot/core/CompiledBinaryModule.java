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
    Objects.requireNonNull(versionCommand);
    Objects.requireNonNull(expectedVersion);
    if (binaryName.isBlank()) {
      throw new IllegalArgumentException("Binary name must not be blank");
    }
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
        continueOnError);
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
        continueOnError);
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
        continueOnError,
        Optional.empty(),
        Optional.empty());
  }
}
