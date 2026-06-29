package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.executor.ExecutionPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Prints the phase-ordered execution plan for a config without installing anything. */
@Command(name = "plan", description = "Show the execution plan without making any changes")
public final class PlanCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Spec private CommandSpec spec;

  @Option(
      names = {"--skip-already-installed"},
      description = "Show which items would be skipped by probe/state")
  private boolean skipAlreadyInstalled;

  @Option(
      names = {"--profile"},
      defaultValue = "default",
      paramLabel = "PROFILE")
  private String profile;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private PlanFormat format;

  @Option(names = "--show-commands", description = "Show executor command previews when available")
  private boolean showCommands;

  @Override
  public void run() {
    var context = ApplicationContext.create(true, profile, skipAlreadyInstalled, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());

    ExecutionPlan plan;
    try {
      plan = context.executionPlanBuilder().build(config);
    } catch (dev.sysboot.executor.CyclicDependencyException e) {
      throw new CliFailureException(
          ExitCode.CONFIGURATION_ERROR, "Cycle detected: " + e.getMessage(), e);
    }

    Map<String, InstallationStatus> probeResults =
        skipAlreadyInstalled
            ? context.parallelProbeRunner().probeAll(config.modules(), ignored -> {})
            : Map.of();

    switch (format) {
      case JSON -> JsonOutput.write(spec.commandLine().getOut(), jsonPlan(plan, probeResults));
      case TABLE -> writeTablePlan(plan, probeResults);
      case TREE -> writeTreePlan(plan, probeResults);
      case TEXT -> writeTextPlan(plan, probeResults);
    }
  }

  private void writeTextPlan(ExecutionPlan plan, Map<String, InstallationStatus> probeResults) {
    var out = spec.commandLine().getOut();
    out.println("Execution plan for: " + plan.profileName());
    out.println();
    writeTextSourceSetups(plan);

    for (int i = 0; i < plan.phases().size(); i++) {
      ExecutionPlan.Phase phase = plan.phases().get(i);
      out.printf("Phase %d: %-25s [%s]%n", i + 1, phase.name(), dependencyLabel(phase));

      for (ExecutionPlan.Module module : phase.modules()) {
        for (ExecutionPlan.Item item : module.items()) {
          String skipLabel = computeSkipLabel(item.item().key(), probeResults);
          out.printf("  • %-35s %s%n", item.item().displayName(), skipLabel);
          if (showCommands && item.commandPreview().isPresent()) {
            out.printf("    $ %s%n", commandPreview(item));
          }
        }
      }

      switch (phase.restartEffect()) {
        case PROMPT_LOGOUT -> out.println("  → After this phase: RESTART REQUIRED");
        case REQUIRES_NEW_SHELL -> out.println("  → After this phase: new-shell wrapper");
        case NONE -> {}
      }
      out.println();
    }
    writeSkippedText(plan.skippedEntries());
  }

  private void writeTablePlan(ExecutionPlan plan, Map<String, InstallationStatus> probeResults) {
    var out = spec.commandLine().getOut();
    out.printf("%-22s %-24s %-35s %s%n", "PHASE", "MODULE", "ITEM", "STATUS");
    out.println("-".repeat(100));
    for (ExecutionPlan.Module module : plan.sourceSetups()) {
      writeTableModule("source-setup", module, probeResults);
    }
    for (ExecutionPlan.Phase phase : plan.phases()) {
      for (ExecutionPlan.Module module : phase.modules()) {
        writeTableModule(phase.name(), module, probeResults);
      }
    }
    for (SkippedPlanEntry skipped : plan.skippedEntries()) {
      out.printf(
          "%-22s %-24s %-35s skipped: %s%n",
          "manifest-plan", skipped.name(), skipped.kind(), skipped.reason());
    }
  }

  private void writeTreePlan(ExecutionPlan plan, Map<String, InstallationStatus> probeResults) {
    var out = spec.commandLine().getOut();
    out.println("Execution plan for: " + plan.profileName());
    writeTreeSourceSetups(plan, probeResults);
    for (ExecutionPlan.Phase phase : plan.phases()) {
      out.printf("└─ %s [%s]%n", phase.name(), dependencyLabel(phase));
      for (ExecutionPlan.Module module : phase.modules()) {
        out.printf("   └─ %s (%s)%n", module.name(), module.type());
        for (ExecutionPlan.Item item : module.items()) {
          out.printf(
              "      └─ %s - %s%n",
              item.item().displayName(), computeSkipLabel(item.item().key(), probeResults));
          if (showCommands && item.commandPreview().isPresent()) {
            out.printf("         $ %s%n", commandPreview(item));
          }
        }
      }
    }
    writeSkippedTree(plan.skippedEntries());
  }

  private String dependencyLabel(ExecutionPlan.Phase phase) {
    if (phase.dependsOn().isEmpty()) {
      return "no deps";
    }
    return "after: " + String.join(", ", phase.dependsOn());
  }

  private String commandPreview(ExecutionPlan.Item item) {
    return String.join(" ", item.commandPreview().orElseThrow());
  }

  private Map<String, Object> jsonPlan(
      ExecutionPlan plan, Map<String, InstallationStatus> probeResults) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", plan.profileName());
    output.put(
        "sourceSetups",
        plan.sourceSetups().stream().map(module -> jsonModule(module, probeResults)).toList());
    output.put(
        "phases", plan.phases().stream().map(phase -> jsonPhase(phase, probeResults)).toList());
    output.put("skippedEntries", plan.skippedEntries().stream().map(this::jsonSkipped).toList());
    return output;
  }

  private void writeSkippedText(List<SkippedPlanEntry> skippedEntries) {
    var out = spec.commandLine().getOut();
    if (skippedEntries.isEmpty()) {
      return;
    }
    out.println("Skipped WorkstationProfile entries:");
    for (SkippedPlanEntry skipped : skippedEntries) {
      out.printf("  • %-35s %s%n", skipped.name(), skipped.reason());
    }
    out.println();
  }

  private void writeTextSourceSetups(ExecutionPlan plan) {
    var out = spec.commandLine().getOut();
    if (plan.sourceSetups().isEmpty()) {
      return;
    }
    out.println("Source setup:");
    for (ExecutionPlan.Module module : plan.sourceSetups()) {
      for (ExecutionPlan.Item item : module.items()) {
        out.printf("  • %-35s would run%n", item.item().displayName());
        if (showCommands && item.commandPreview().isPresent()) {
          out.printf("    $ %s%n", commandPreview(item));
        }
      }
    }
    out.println();
  }

  private void writeTableModule(
      String phase, ExecutionPlan.Module module, Map<String, InstallationStatus> probeResults) {
    var out = spec.commandLine().getOut();
    for (ExecutionPlan.Item item : module.items()) {
      out.printf(
          "%-22s %-24s %-35s %s%n",
          phase,
          module.name(),
          item.item().displayName(),
          computeSkipLabel(item.item().key(), probeResults));
      if (showCommands && item.commandPreview().isPresent()) {
        out.printf("%-22s %-24s %-35s $ %s%n", "", "", "", commandPreview(item));
      }
    }
  }

  private void writeTreeSourceSetups(
      ExecutionPlan plan, Map<String, InstallationStatus> probeResults) {
    var out = spec.commandLine().getOut();
    if (plan.sourceSetups().isEmpty()) {
      return;
    }
    out.println("└─ source setup");
    for (ExecutionPlan.Module module : plan.sourceSetups()) {
      out.printf("   └─ %s (%s)%n", module.name(), module.type());
      for (ExecutionPlan.Item item : module.items()) {
        out.printf(
            "      └─ %s - %s%n",
            item.item().displayName(), computeSkipLabel(item.item().key(), probeResults));
        if (showCommands && item.commandPreview().isPresent()) {
          out.printf("         $ %s%n", commandPreview(item));
        }
      }
    }
  }

  private void writeSkippedTree(List<SkippedPlanEntry> skippedEntries) {
    var out = spec.commandLine().getOut();
    if (skippedEntries.isEmpty()) {
      return;
    }
    out.println("└─ skipped WorkstationProfile entries");
    for (SkippedPlanEntry skipped : skippedEntries) {
      out.printf("   └─ %s (%s) - %s%n", skipped.name(), skipped.kind(), skipped.reason());
    }
  }

  private Map<String, Object> jsonSkipped(SkippedPlanEntry skipped) {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", skipped.name());
    output.put("kind", skipped.kind());
    output.put("status", "skipped");
    output.put("reason", skipped.reason());
    return output;
  }

  private Map<String, Object> jsonPhase(
      ExecutionPlan.Phase phase, Map<String, InstallationStatus> probeResults) {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", phase.name());
    output.put("dependsOn", phase.dependsOn());
    output.put("restartEffect", phase.restartEffect().name().toLowerCase());
    output.put(
        "modules",
        phase.modules().stream().map(module -> jsonModule(module, probeResults)).toList());
    return output;
  }

  private Map<String, Object> jsonModule(
      ExecutionPlan.Module module, Map<String, InstallationStatus> probeResults) {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", module.name());
    output.put("type", module.type());
    output.put("items", module.items().stream().map(item -> jsonItem(item, probeResults)).toList());
    return output;
  }

  private Map<String, Object> jsonItem(
      ExecutionPlan.Item item, Map<String, InstallationStatus> probeResults) {
    var output = new LinkedHashMap<String, Object>();
    output.put("key", item.item().key());
    output.put("displayName", item.item().displayName());
    output.put("type", item.item().itemType().name().toLowerCase());
    output.put(
        "packageManager",
        item.item().packageManager().map(kind -> kind.name().toLowerCase()).orElse(null));
    output.put("status", computeSkipLabel(item.item().key(), probeResults));
    output.put("commandPreview", item.commandPreview().orElse(List.of()));
    return output;
  }

  private String computeSkipLabel(String item, Map<String, InstallationStatus> probeResults) {
    if (!skipAlreadyInstalled) return "would run";
    InstallationStatus status = probeResults.get(item);
    if (status == null) return "would run";
    return switch (status) {
      case InstallationStatus.InstalledByProbe p ->
          "○ would skip (probe: installed"
              + (p.detectedVersion() != null ? " " + p.detectedVersion() : "")
              + ")";
      case InstallationStatus.InstalledFromState s ->
          "○ would skip (state: " + s.installedAt().toString().substring(0, 10) + ")";
      case InstallationStatus.NotInstalled ignored -> "would run";
      case InstallationStatus.Unknown ignored -> "would run (probe unknown)";
    };
  }

  private enum PlanFormat {
    TEXT,
    TABLE,
    TREE,
    JSON
  }
}
