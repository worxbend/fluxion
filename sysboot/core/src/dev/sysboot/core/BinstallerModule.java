package dev.sysboot.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A binary tool distribution applied through <a href="https://github.com/worxbend/binstaller">
 * binstaller</a>.
 *
 * <p>binstaller already resolves versions, downloads, verifies, extracts, installs under an apps
 * directory, and manages symlinks, and it has its own plan/apply/lock model. Fluxion delegates to
 * it rather than reimplementing any of that: this module is a reference to a {@code
 * BinaryDistributionProfile} plus the selection and locking options to pass through.
 *
 * @param config path to the {@code BinaryDistributionProfile} YAML
 * @param only install only these tools; empty means all
 * @param skip omit these tools
 * @param locked require a compatible lock file before applying
 * @param lockFile explicit lock file path used when {@code locked} is set
 * @param installerVersion binstaller release to use when Fluxion has to install it
 * @param binstallerBinary executable name, for unusual installations
 */
public record BinstallerModule(
    ModuleName name,
    Path config,
    List<String> only,
    List<String> skip,
    boolean locked,
    Optional<Path> lockFile,
    String installerVersion,
    String binstallerBinary,
    Optional<String> probeCommand,
    boolean continueOnError)
    implements BootstrapModule {

  public BinstallerModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(config);
    Objects.requireNonNull(installerVersion);
    Objects.requireNonNull(binstallerBinary);
    Objects.requireNonNull(lockFile);
    Objects.requireNonNull(probeCommand);
    only = List.copyOf(Objects.requireNonNull(only));
    skip = List.copyOf(Objects.requireNonNull(skip));
    ReleaseTagPolicy.requireExact("installerVersion", installerVersion);
    if (binstallerBinary.isBlank()) {
      throw new IllegalArgumentException("binstallerBinary must not be blank");
    }
    if (locked && lockFile.isEmpty()) {
      throw new IllegalArgumentException(
          "locked requires lockFile so the profile pins a specific lock");
    }
  }

  public BinstallerModule(ModuleName name, Path config) {
    this(
        name,
        config,
        List.of(),
        List.of(),
        false,
        Optional.empty(),
        KnownTools.BINSTALLER.version(),
        KnownTools.BINSTALLER.executableName(),
        Optional.empty(),
        false);
  }

  /** Item key used for state, skip decisions, and event reporting. */
  public String itemKey() {
    return config.toString();
  }
}
