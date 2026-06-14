package dev.sysboot.cli;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.Phase;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.executor.PhaseExecutionPlanner;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "plan", description = "Show the execution plan without making any changes")
public final class PlanCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Option(
      names = {"--skip-already-installed"},
      description = "Show which items would be skipped by probe/state")
  private boolean skipAlreadyInstalled;

  @Option(
      names = {"--profile"},
      defaultValue = "default",
      paramLabel = "PROFILE")
  private String profile;

  @Override
  public void run() {
    var context = ApplicationContext.create(true, profile, skipAlreadyInstalled, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());

    List<Phase> ordered;
    try {
      ordered = new PhaseExecutionPlanner().plan(config.phases());
    } catch (dev.sysboot.executor.CyclicDependencyException e) {
      System.err.println("✗ Cycle detected: " + e.getMessage());
      return;
    }

    Map<String, InstallationStatus> probeResults =
        skipAlreadyInstalled
            ? context.parallelProbeRunner().probeAll(config.modules(), ignored -> {})
            : Map.of();

    System.out.println("Execution plan for: " + config.profileName().value());
    System.out.println();

    for (int i = 0; i < ordered.size(); i++) {
      Phase phase = ordered.get(i);
      String deps =
          phase.dependsOn().isEmpty()
              ? "no deps"
              : "after: "
                  + phase.dependsOn().stream()
                      .map(p -> p.value())
                      .reduce((a, b) -> a + ", " + b)
                      .orElse("");

      System.out.printf("Phase %d: %-25s [%s]%n", i + 1, phase.name().value(), deps);

      for (var module : phase.modules()) {
        for (var item : flatItems(module)) {
          String skipLabel = computeSkipLabel(item, probeResults);
          System.out.printf("  • %-35s %s%n", item, skipLabel);
        }
      }

      switch (phase.restartPolicy()) {
        case RestartPolicy.PromptLogout pr ->
            System.out.println("  → After this phase: RESTART REQUIRED");
        case RestartPolicy.RequiresNewShell rns ->
            System.out.println(
                "  → After this phase: new-shell wrapper (" + rns.shell().binaryName() + ")");
        case RestartPolicy.None ignored -> {}
      }
      System.out.println();
    }
  }

  private List<String> flatItems(dev.sysboot.core.BootstrapModule module) {
    return switch (module) {
      case dev.sysboot.core.PackageModule pm -> pm.packages().stream().map(p -> p.value()).toList();
      case dev.sysboot.core.FlatpakModule fm -> fm.appIds();
      case dev.sysboot.core.ZypperModule zm -> zm.packages().stream().map(p -> p.value()).toList();
      default -> List.of(module.name().value());
    };
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
