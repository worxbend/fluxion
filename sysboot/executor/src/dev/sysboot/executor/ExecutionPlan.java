package dev.sysboot.executor;

import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.SkippedPlanEntry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ExecutionPlan(
    String profileName,
    List<ExecutionPlan.Module> sourceSetups,
    List<ExecutionPlan.Phase> phases,
    List<SkippedPlanEntry> skippedEntries) {

  public ExecutionPlan {
    Objects.requireNonNull(profileName);
    sourceSetups = List.copyOf(Objects.requireNonNull(sourceSetups));
    phases = List.copyOf(Objects.requireNonNull(phases));
    skippedEntries = List.copyOf(Objects.requireNonNull(skippedEntries));
  }

  public ExecutionPlan(
      String profileName, List<ExecutionPlan.Phase> phases, List<SkippedPlanEntry> skippedEntries) {
    this(profileName, List.of(), phases, skippedEntries);
  }

  public ExecutionPlan(String profileName, List<ExecutionPlan.Phase> phases) {
    this(profileName, List.of(), phases, List.of());
  }

  public record Phase(
      String name, List<String> dependsOn, RestartEffect restartEffect, List<Module> modules) {

    public Phase {
      Objects.requireNonNull(name);
      dependsOn = List.copyOf(Objects.requireNonNull(dependsOn));
      Objects.requireNonNull(restartEffect);
      modules = List.copyOf(Objects.requireNonNull(modules));
    }
  }

  public record Module(String name, String type, List<Item> items) {

    public Module {
      Objects.requireNonNull(name);
      Objects.requireNonNull(type);
      items = List.copyOf(Objects.requireNonNull(items));
    }
  }

  public record Item(ModuleItem item, Optional<List<String>> commandPreview) {

    public Item {
      Objects.requireNonNull(item);
      commandPreview = commandPreview == null ? Optional.empty() : commandPreview.map(List::copyOf);
    }
  }

  public enum RestartEffect {
    NONE,
    PROMPT_LOGOUT,
    REQUIRES_NEW_SHELL
  }
}
