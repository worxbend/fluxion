package dev.sysboot.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class BootstrapConfig {

  private final ProfileName profileName;
  private final OsTarget target;
  private final BootstrapPolicy policy;
  private final List<Phase> phases;
  private final List<SkippedPlanEntry> skippedPlanEntries;
  private final List<SourceSetup> sourceSetups;

  private BootstrapConfig(
      ProfileName profileName,
      OsTarget target,
      BootstrapPolicy policy,
      List<Phase> phases,
      List<SkippedPlanEntry> skippedPlanEntries,
      List<SourceSetup> sourceSetups) {
    this.profileName = profileName;
    this.target = target;
    this.policy = policy;
    this.phases = List.copyOf(phases);
    this.skippedPlanEntries = List.copyOf(skippedPlanEntries);
    this.sourceSetups = List.copyOf(sourceSetups);
  }

  public ProfileName profileName() {
    return profileName;
  }

  public OsTarget target() {
    return target;
  }

  public BootstrapPolicy policy() {
    return policy;
  }

  public List<Phase> phases() {
    return phases;
  }

  public List<SkippedPlanEntry> skippedPlanEntries() {
    return skippedPlanEntries;
  }

  public List<SourceSetup> sourceSetups() {
    return sourceSetups;
  }

  /** Flattened view of all modules across all phases — used by probing and legacy code. */
  public List<BootstrapModule> modules() {
    return phases.stream().flatMap(p -> p.modules().stream()).toList();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private ProfileName profileName;
    private OsTarget target;
    private BootstrapPolicy policy = BootstrapPolicy.empty();
    private final List<Phase> phases = new ArrayList<>();
    private final List<BootstrapModule> pendingModules = new ArrayList<>();
    private final List<SkippedPlanEntry> skippedPlanEntries = new ArrayList<>();
    private final List<SourceSetup> sourceSetups = new ArrayList<>();

    public Builder profileName(ProfileName name) {
      this.profileName = Objects.requireNonNull(name);
      return this;
    }

    public Builder target(OsTarget target) {
      this.target = Objects.requireNonNull(target);
      return this;
    }

    public Builder policy(BootstrapPolicy policy) {
      this.policy = Objects.requireNonNull(policy);
      return this;
    }

    /** Legacy helper: adds a module to an implicit "default" phase. */
    public Builder addModule(BootstrapModule module) {
      pendingModules.add(Objects.requireNonNull(module));
      return this;
    }

    /** Legacy helper: adds multiple modules to the implicit "default" phase. */
    public Builder modules(List<BootstrapModule> moduleList) {
      moduleList.forEach(m -> pendingModules.add(Objects.requireNonNull(m)));
      return this;
    }

    public Builder addPhase(Phase phase) {
      phases.add(Objects.requireNonNull(phase));
      return this;
    }

    public Builder skippedPlanEntries(List<SkippedPlanEntry> entries) {
      entries.forEach(entry -> skippedPlanEntries.add(Objects.requireNonNull(entry)));
      return this;
    }

    public Builder sourceSetups(List<SourceSetup> entries) {
      entries.forEach(entry -> sourceSetups.add(Objects.requireNonNull(entry)));
      return this;
    }

    public BootstrapConfig build() {
      Objects.requireNonNull(profileName, "Profile name is required");
      Objects.requireNonNull(target, "OS target is required");
      Objects.requireNonNull(policy, "Bootstrap policy is required");

      List<Phase> allPhases = new ArrayList<>(phases);
      if (!pendingModules.isEmpty()) {
        validateUniqueNames(pendingModules);
        allPhases.add(
            new Phase(
                new PhaseName("default"),
                "Default phase",
                List.copyOf(pendingModules),
                List.of(),
                new RestartPolicy.None(),
                true));
      }

      if (allPhases.isEmpty()) {
        throw new IllegalStateException("At least one phase or module is required");
      }
      validateUniquePhaseNames(allPhases);
      validateUniqueModuleNames(allPhases);
      return new BootstrapConfig(
          profileName, target, policy, allPhases, skippedPlanEntries, sourceSetups);
    }

    private void validateUniqueNames(List<BootstrapModule> mods) {
      Set<String> seen = new HashSet<>();
      for (BootstrapModule m : mods) {
        if (!seen.add(m.name().value())) {
          throw new IllegalStateException("Duplicate module name: " + m.name().value());
        }
      }
    }

    private void validateUniquePhaseNames(List<Phase> allPhases) {
      Set<String> seen = new HashSet<>();
      for (Phase p : allPhases) {
        if (!seen.add(p.name().value())) {
          throw new IllegalStateException("Duplicate phase name: " + p.name().value());
        }
      }
    }

    private void validateUniqueModuleNames(List<Phase> allPhases) {
      Set<String> seen = new HashSet<>();
      for (Phase phase : allPhases) {
        for (BootstrapModule module : phase.modules()) {
          if (!seen.add(module.name().value())) {
            throw new IllegalStateException("Duplicate module name: " + module.name().value());
          }
        }
      }
    }
  }
}
