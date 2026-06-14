package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

public record Phase(
    PhaseName name,
    String description,
    List<BootstrapModule> modules,
    List<PhaseName> dependsOn,
    RestartPolicy restartPolicy,
    boolean continueOnModuleError) {

  public Phase {
    Objects.requireNonNull(name);
    Objects.requireNonNull(description);
    Objects.requireNonNull(modules);
    Objects.requireNonNull(dependsOn);
    Objects.requireNonNull(restartPolicy);
    modules = List.copyOf(modules);
    dependsOn = List.copyOf(dependsOn);
  }

  public Phase(
      PhaseName name,
      String description,
      List<BootstrapModule> modules,
      List<PhaseName> dependsOn,
      RestartPolicy restartPolicy) {
    this(name, description, modules, dependsOn, restartPolicy, true);
  }
}
