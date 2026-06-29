package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

public record SdkmanModule(
    ModuleName name, List<SdkmanPackage> packages, boolean continueOnError)
    implements BootstrapModule {

  public SdkmanModule {
    Objects.requireNonNull(name, "Module name must not be null");
    Objects.requireNonNull(packages, "SDKMAN package list must not be null");
    packages = List.copyOf(packages);
    if (packages.isEmpty()) {
      throw new IllegalArgumentException(
          "SDKMAN module '" + name.value() + "' must declare at least one package");
    }
  }
}
