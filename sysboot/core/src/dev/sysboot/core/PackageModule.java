package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

public record PackageModule(
    ModuleName name,
    PackageManagerKind packageManager,
    List<PackageName> packages,
    List<PackageManagerAction> actions,
    boolean continueOnError)
    implements BootstrapModule {

  public PackageModule(
      ModuleName name,
      PackageManagerKind packageManager,
      List<PackageName> packages,
      boolean continueOnError) {
    this(name, packageManager, packages, List.of(), continueOnError);
  }

  public PackageModule {
    Objects.requireNonNull(name, "Module name must not be null");
    Objects.requireNonNull(packageManager, "Package manager must not be null");
    Objects.requireNonNull(packages, "Package list must not be null");
    Objects.requireNonNull(actions, "Package manager action list must not be null");
    packages = List.copyOf(packages);
    actions = List.copyOf(actions);
    if (packages.isEmpty()) {
      throw new IllegalArgumentException(
          "Package module '" + name.value() + "' must declare at least one package");
    }
  }
}
