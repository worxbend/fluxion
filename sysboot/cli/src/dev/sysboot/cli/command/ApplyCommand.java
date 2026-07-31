package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.PlainExecutionReport;
import dev.sysboot.cli.output.StdoutExecutionEventListener;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.ExecutionApproval;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionPausedException;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.StepResult;
import dev.sysboot.executor.ExecutionPlan;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.PhaseExecutionPlanner;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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
      description = "Approve confirmation-protected items for unattended execution")
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
      names = {"--reset-state"},
      description = "Delete saved state before applying this profile")
  private boolean resetState;

  @Option(
      names = {"--probe-only"},
      description = "Run probes and print status without installing")
  private boolean probeOnly;

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
    var stateRepository = new JsonStateRepository(new ObjectMapper());
    ExecutionApproval approval = yes ? ExecutionApproval.approveAll() : ExecutionApproval.denyAll();
    BootstrapConfig config;
    try (var configContext = ApplicationContext.create(true)) {
      config = configContext.configLoader().load(options.resolvedConfigFile());
    }
    SemanticConfigValidation.requireValid(config);
    BootstrapConfig filtered = applyFilters(config);
    boolean effectiveDryRun = dryRun || filtered.policy().dryRunDefault().orElse(false);
    rejectUnapprovedConfirmations(filtered, effectiveDryRun);
    try (var context =
        ApplicationContext.create(
            !useTui, profile, effectiveSkip, reProbe, approval, effectiveDryRun)) {
      execute(context, stateRepository, useTui, config, filtered, effectiveDryRun);
    }
  }

  /** Closing the context zeroes the cached sudo password and stops its keepalive. */
  private void execute(
      ApplicationContext context,
      JsonStateRepository stateRepository,
      boolean useTui,
      BootstrapConfig config,
      BootstrapConfig filtered,
      boolean effectiveDryRun) {
    preflightBeforeReset(
        effectiveDryRun, probeOnly, false, () -> context.preflight(filtered), () -> {});
    Runnable apply =
        () -> {
          if (resetState && !effectiveDryRun) {
            stateRepository.reset(profile);
          }
          executeSelected(context, stateRepository, useTui, config, filtered, effectiveDryRun);
        };
    if (resetState && !effectiveDryRun) {
      stateRepository.withGlobalMutationLock(apply);
    } else {
      apply.run();
    }
  }

  private void executeSelected(
      ApplicationContext context,
      JsonStateRepository stateRepository,
      boolean useTui,
      BootstrapConfig config,
      BootstrapConfig filtered,
      boolean effectiveDryRun) {
    if (probeOnly) {
      runProbeOnly(context, filtered);
      return;
    }

    if (!useTui) {
      runPlain(context, stateRepository, config, filtered, effectiveDryRun);
      return;
    }
    runTui(context, config, filtered, effectiveDryRun);
  }

  private void runPlain(
      ApplicationContext context,
      JsonStateRepository stateRepository,
      BootstrapConfig config,
      BootstrapConfig filtered,
      boolean effectiveDryRun) {
    var listener =
        new StdoutExecutionEventListener(
                event -> resumeCommandFor(event, config),
                () -> Optional.of(stateRepository.path(profile)))
            .streamingOutput(options.verbose());
    writePlainReport(context, filtered, stateRepository, effectiveDryRun);
    var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
    try {
      if (effectiveDryRun) {
        context.orchestrator().dryRun(filtered, listener);
      } else {
        InterruptibleRun.run(
            cancellation ->
                runPlainApply(context, config, filtered, listener, cancellation, failure),
            this::printCancellationNotice);
      }
    } finally {
      listener.printSummary();
    }
    if (failure.get() instanceof ExecutionPausedException paused) {
      throw paused;
    }
  }

  private void runPlainApply(
      ApplicationContext context,
      BootstrapConfig config,
      BootstrapConfig filtered,
      StdoutExecutionEventListener listener,
      dev.sysboot.core.CancellationSignal cancellation,
      java.util.concurrent.atomic.AtomicReference<RuntimeException> failure) {
    try {
      context
          .orchestrator()
          .execute(config, selectedPhases(config, filtered), listener, cancellation);
    } catch (ExecutionPausedException e) {
      failure.set(e);
    }
  }

  private void printCancellationNotice() {
    System.out.println(
        System.lineSeparator()
            + "Stopping after the current step; press Ctrl-C again to force quit.");
  }

  private void runTui(
      ApplicationContext context,
      BootstrapConfig config,
      BootstrapConfig filtered,
      boolean effectiveDryRun) {
    var tui =
        context.tuiApp().orElseThrow(() -> new IllegalStateException("TUI mode is not available"));
    tui.showCommandOutput(options.verbose());
    var failure = new java.util.concurrent.atomic.AtomicReference<RuntimeException>();
    InterruptibleRun.run(
        cancellation -> {
          try {
            tui.run(config, filtered, effectiveDryRun, cancellation);
          } catch (java.io.IOException e) {
            failure.set(
                new CliFailureException(ExitCode.IO_ERROR, "TUI error: " + e.getMessage(), e));
          }
        },
        () -> {});
    if (failure.get() != null) {
      throw failure.get();
    }
  }

  private void writePlainReport(
      ApplicationContext context,
      BootstrapConfig config,
      JsonStateRepository stateRepository,
      boolean effectiveDryRun) {
    ExecutionPlan plan = buildPlan(context, config);
    var out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
    PlainExecutionReport.writeHeader(
        out,
        "apply",
        effectiveDryRun ? "dry-run" : "live",
        plan.profileName(),
        context.hostFactsProvider().facts(),
        Optional.of(stateRepository.path(profile)));
    PlainExecutionReport.writeWorkstationSelection(out, plan);
  }

  static void preflightBeforeReset(
      boolean effectiveDryRun,
      boolean probeOnly,
      boolean resetState,
      Runnable preflight,
      Runnable reset) {
    if (!effectiveDryRun && !probeOnly) {
      preflight.run();
    }
    if (resetState && !effectiveDryRun) {
      reset.run();
    }
  }

  private ExecutionPlan buildPlan(ApplicationContext context, BootstrapConfig config) {
    try {
      return context.executionPlanBuilder().build(config);
    } catch (dev.sysboot.executor.CyclicDependencyException e) {
      throw new CliFailureException(
          ExitCode.CONFIGURATION_ERROR, "Cycle detected: " + e.getMessage(), e);
    }
  }

  private void runProbeOnly(ApplicationContext context, BootstrapConfig config) {
    System.out.println("Probe-only mode: checking installation status...");
    var results =
        context
            .parallelProbeRunner()
            .probeAll(buildPlan(context, config), item -> System.out.println("  Probed: " + item));
    results.forEach(
        (key, status) ->
            System.out.println("  " + key + " → " + status.getClass().getSimpleName()));
  }

  private void rejectUnapprovedConfirmations(BootstrapConfig config, boolean effectiveDryRun) {
    if (yes || effectiveDryRun || probeOnly) {
      return;
    }
    List<String> guardedItems = config.modules().stream().flatMap(this::guardedItems).toList();
    if (guardedItems.isEmpty()) {
      return;
    }
    throw new CliFailureException(
        ExitCode.INVALID_INPUT,
        "Explicit confirmation required for "
            + String.join(", ", guardedItems)
            + ". Re-run with --yes; guarded items are not prompted interactively.");
  }

  private java.util.stream.Stream<String> guardedItems(BootstrapModule module) {
    return switch (module) {
      case ShellCommandModule commands ->
          commands.items().stream()
              .filter(item -> item.confirm().isPresent())
              .map(item -> module.name().value() + "/" + item.name());
      case ShellScriptModule scripts ->
          scripts.items().stream()
              .filter(item -> item.confirm().isPresent())
              .map(item -> module.name().value() + "/" + item.name());
      default -> java.util.stream.Stream.empty();
    };
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
    if (phases == config.phases()) {
      return config;
    }
    Set<PhaseName> retained =
        phases.stream().map(Phase::name).collect(Collectors.toUnmodifiableSet());
    phases = phases.stream().map(phase -> retainDependencies(phase, retained)).toList();

    var builder =
        BootstrapConfig.builder()
            .profileName(config.profileName())
            .target(config.target())
            .policy(config.policy())
            .skippedPlanEntries(config.skippedPlanEntries())
            .sourceSetups(config.sourceSetups());
    phases.forEach(builder::addPhase);
    return builder.build();
  }

  private List<Phase> selectedPhases(BootstrapConfig config, BootstrapConfig filtered) {
    Set<PhaseName> selected =
        filtered.phases().stream().map(Phase::name).collect(Collectors.toUnmodifiableSet());
    return config.phases().stream().filter(phase -> selected.contains(phase.name())).toList();
  }

  private Phase retainDependencies(Phase phase, Set<PhaseName> retained) {
    List<PhaseName> dependencies = phase.dependsOn().stream().filter(retained::contains).toList();
    return new Phase(
        phase.name(),
        phase.description(),
        phase.modules(),
        dependencies,
        phase.restartPolicy(),
        phase.continueOnModuleError());
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

  private Optional<String> resumeCommandFor(ExecutionEvent event, BootstrapConfig config) {
    // A cancelled run stopped *inside* its phase, so resuming must re-enter that same phase.
    // Falling through to nextPhaseAfter silently skipped every remaining module of it.
    if (event.kind() == dev.sysboot.core.EventKind.CANCELLED) {
      return Optional.of(
          ResumeCommandFormatter.command(
              options.resolvedConfigFile(), profile, event.phaseContext()));
    }
    Optional<String> phase =
        event
            .result()
            .filter(StepResult.Paused.class::isInstance)
            .flatMap(ignored -> phaseContainingModule(config, event.moduleName().value()))
            .or(
                () ->
                    event
                        .phaseContext()
                        .flatMap(completedPhase -> nextPhaseAfter(config, completedPhase)));
    return Optional.of(
        ResumeCommandFormatter.command(options.resolvedConfigFile(), profile, phase));
  }

  private Optional<String> phaseContainingModule(BootstrapConfig config, String moduleName) {
    return config.phases().stream()
        .filter(
            phase ->
                phase.modules().stream()
                    .anyMatch(module -> module.name().value().equals(moduleName)))
        .map(phase -> phase.name().value())
        .findFirst();
  }
}
