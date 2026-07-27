package dev.sysboot.core;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A full system update, dispatched on the package manager.
 *
 * <p>Every distro script in a real bootstrap starts with one of these. Modelling it as its own kind
 * rather than an opaque command buys a correct non-interactive invocation per manager, a timeout
 * long enough for the job, and — most importantly — knowing which non-zero exit codes mean "nothing
 * to do" rather than failure.
 *
 * @param distUpgrade prefer the manager's full/dist upgrade, which may add and remove packages.
 *     Required on rolling releases such as openSUSE Tumbleweed, where a plain update is wrong.
 * @param refreshOnly only refresh metadata, without installing anything
 */
public record SystemUpdateModule(
    ModuleName name,
    PackageManagerKind packageManager,
    boolean distUpgrade,
    boolean refreshOnly,
    Optional<Duration> timeout,
    boolean continueOnError)
    implements BootstrapModule {

  public SystemUpdateModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(packageManager);
    Objects.requireNonNull(timeout);
    if (distUpgrade && refreshOnly) {
      throw new IllegalArgumentException(
          "system-update cannot be both distUpgrade and refreshOnly");
    }
  }

  public String itemKey() {
    return packageManager.name().toLowerCase(java.util.Locale.ROOT) + "-system-update";
  }
}
