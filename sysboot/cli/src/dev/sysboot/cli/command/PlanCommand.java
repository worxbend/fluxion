package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.InstallationStatus;
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
  private OutputFormat format;

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

    if (format == OutputFormat.JSON) {
      JsonOutput.write(spec.commandLine().getOut(), jsonPlan(plan, probeResults));
      return;
    }

    var out = spec.commandLine().getOut();
    out.println("Execution plan for: " + config.profileName().value());
    out.println();

    for (int i = 0; i < plan.phases().size(); i++) {
      ExecutionPlan.Phase phase = plan.phases().get(i);
      String deps =
          phase.dependsOn().isEmpty()
              ? "no deps"
              : "after: " + phase.dependsOn().stream().reduce((a, b) -> a + ", " + b).orElse("");

      out.printf("Phase %d: %-25s [%s]%n", i + 1, phase.name(), deps);

      for (ExecutionPlan.Module module : phase.modules()) {
        for (ExecutionPlan.Item item : module.items()) {
          String skipLabel = computeSkipLabel(item.item().key(), probeResults);
          out.printf("  • %-35s %s%n", item.item().displayName(), skipLabel);
          if (showCommands && item.commandPreview().isPresent()) {
            out.printf("    $ %s%n", String.join(" ", item.commandPreview().orElseThrow()));
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
  }

  private Map<String, Object> jsonPlan(
      ExecutionPlan plan, Map<String, InstallationStatus> probeResults) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", plan.profileName());
    output.put(
        "phases", plan.phases().stream().map(phase -> jsonPhase(phase, probeResults)).toList());
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
}
