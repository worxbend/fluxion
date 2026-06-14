package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

public record ZypperModule(ModuleName name, List<PackageName> packages, boolean continueOnError)
    implements BootstrapModule {

  public ZypperModule {
    Objects.requireNonNull(name, "Module name must not be null");
    Objects.requireNonNull(packages, "Package list must not be null");
    packages = List.copyOf(packages);
    if (packages.isEmpty()) {
      throw new IllegalArgumentException(
          "Zypper module '" + name.value() + "' must declare at least one package");
    }
  }

  public PackageModule asPackageModule() {
    return new PackageModule(name, PackageManagerKind.ZYPPER, packages, continueOnError);
  }
}
