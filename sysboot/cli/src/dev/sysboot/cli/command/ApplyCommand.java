package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.StdoutExecutionEventListener;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.Phase;
import dev.sysboot.executor.PhaseExecutionPlanner;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "apply", aliases = "run", description = "Apply a bootstrap profile")
public final class ApplyCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Option(
      names = {"--phase"},
      description = "Run only these phases (comma-separated)",
      paramLabel = "PHASE[,...]")
  private String phaseFilter;

  @Option(
      names = {"--from-phase"},
      description = "Start from this phase (skip earlier, regardless of state)",
      paramLabel = "PHASE")
  private String fromPhase;

  @Option(
      names = {"--dry-run"},
      description = "Show what would be executed without changes")
  private boolean dryRun;

  @Option(
      names = {"--yes", "-y"},
      description = "Approve unattended execution")
  private boolean yes;

  @Option(
      names = {"--skip-already-installed"},
      description = "Skip items/phases in state or confirmed by live probe")
  private boolean skipAlreadyInstalled;

  @Option(
      names = {"--re-probe"},
      description = "Ignore state file; always live-probe (implies --skip-already-installed)")
  private boolean reProbe;

  @Option(
      names = {"--probe-only"},
      description = "Run probes and print status without installing")
  private boolean probeOnly;

  @Option(
      names = {"--parallel-phases"},
      description = "Run independent phases concurrently (default: false)")
  private boolean parallelPhases;

  @Option(
      names = {"--profile"},
      description = "Profile name for state tracking",
      paramLabel = "PROFILE",
      defaultValue = "default")
  private String profile;

  @Override
  public void run() {
    boolean effectiveSkip = skipAlreadyInstalled || reProbe;
    boolean useTui = options.useTui();
    var context = ApplicationContext.create(!useTui, profile, effectiveSkip, reProbe);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());
    BootstrapConfig filtered = applyFilters(config);

    if (probeOnly) {
      runProbeOnly(context, filtered);
      return;
    }

    if (!useTui) {
      var listener =
          new StdoutExecutionEventListener(
              event ->
                  event
                      .phaseContext()
                      .map(
                          phase ->
                              ResumeCommandFormatter.command(
                                  options.resolvedConfigFile(),
                                  profile,
                                  nextPhaseAfter(filtered, phase))));
      if (dryRun) {
        context.orchestrator().dryRun(filtered, listener);
      } else {
        context.orchestrator().execute(filtered, listener);
      }
    } else {
      try {
        context
            .tuiApp()
            .orElseThrow(() -> new IllegalStateException("TUI mode is not available"))
            .run(filtered, dryRun);
      } catch (java.io.IOException e) {
        throw new CliFailureException(ExitCode.IO_ERROR, "TUI error: " + e.getMessage(), e);
      }
    }
  }

  private void runProbeOnly(ApplicationContext context, BootstrapConfig config) {
    System.out.println("Probe-only mode: checking installation status...");
    var results =
        context
            .parallelProbeRunner()
            .probeAll(config.modules(), item -> System.out.println("  Probed: " + item));
    results.forEach(
        (key, status) ->
            System.out.println("  " + key + " → " + status.getClass().getSimpleName()));
  }

  private BootstrapConfig applyFilters(BootstrapConfig config) {
    List<Phase> phases = config.phases();

    if (phaseFilter != null && !phaseFilter.isBlank()) {
      Set<String> allowed =
          Arrays.stream(phaseFilter.split(","))
              .map(String::strip)
              .collect(Collectors.toUnmodifiableSet());
      validatePhaseFilter(allowed, config);
      phases = phases.stream().filter(p -> allowed.contains(p.name().value())).toList();
    } else if (fromPhase != null && !fromPhase.isBlank()) {
      List<Phase> ordered;
      try {
        ordered = new PhaseExecutionPlanner().plan(phases);
      } catch (dev.sysboot.executor.CyclicDependencyException e) {
        throw new CliFailureException(
            ExitCode.CONFIGURATION_ERROR, "Cycle in phase graph: " + e.getMessage(), e);
      }
      int startIdx = -1;
      for (int i = 0; i < ordered.size(); i++) {
        if (ordered.get(i).name().value().equals(fromPhase)) {
          startIdx = i;
          break;
        }
      }
      if (startIdx < 0) {
        throw unknownPhase(fromPhase, config);
      }
      phases = ordered.subList(startIdx, ordered.size());
    }

    if (phases.isEmpty()) {
      throw unknownPhase(phaseFilter, config);
    }
    if (phases == config.phases()) return config;

    var builder =
        BootstrapConfig.builder()
            .profileName(config.profileName())
            .target(config.target())
            .policy(config.policy())
            .skippedPlanEntries(config.skippedPlanEntries());
    phases.forEach(builder::addPhase);
    return builder.build();
  }

  private void validatePhaseFilter(Set<String> allowed, BootstrapConfig config) {
    Set<String> valid =
        config.phases().stream()
            .map(phase -> phase.name().value())
            .collect(Collectors.toUnmodifiableSet());
    var unknown = allowed.stream().filter(phase -> !valid.contains(phase)).findFirst();
    unknown.ifPresent(
        phase -> {
          throw unknownPhase(phase, config);
        });
  }

  private CliFailureException unknownPhase(String phaseName, BootstrapConfig config) {
    String validPhases =
        config.phases().stream()
            .map(phase -> phase.name().value())
            .collect(Collectors.joining(", "));
    return new CliFailureException(
        ExitCode.CONFIGURATION_ERROR,
        "Unknown phase '" + phaseName + "'. Valid phases: " + validPhases);
  }

  private Optional<String> nextPhaseAfter(BootstrapConfig config, String completedPhase) {
    List<Phase> ordered = new PhaseExecutionPlanner().plan(config.phases());
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).name().value().equals(completedPhase)) {
        return i + 1 < ordered.size()
            ? Optional.of(ordered.get(i + 1).name().value())
            : Optional.empty();
      }
    }
    return Optional.empty();
  }
}
