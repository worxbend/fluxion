package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ExecutionPausedException;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

final class PhaseExecutionRunner {

  private static final String PHASE_FAILURE_REASON = "Phase stopped after a module failure";

  private final PhaseExecutionPlanner planner;
  private final PhaseFingerprintCalculator fingerprintCalculator;
  private final RunStateRecorder stateRecorder;
  private final ModuleDispatcher moduleDispatcher;
  private final DryRunPlanner dryRunPlanner;
  private final ItemExecution itemExecution;
  private final ShellRunner primaryRunner;

  PhaseExecutionRunner(
      PhaseExecutionPlanner planner,
      PhaseFingerprintCalculator fingerprintCalculator,
      RunStateRecorder stateRecorder,
      ModuleDispatcher moduleDispatcher,
      DryRunPlanner dryRunPlanner,
      ItemExecution itemExecution,
      ShellRunner primaryRunner) {
    this.planner = planner;
    this.fingerprintCalculator = fingerprintCalculator;
    this.stateRecorder = stateRecorder;
    this.moduleDispatcher = moduleDispatcher;
    this.dryRunPlanner = dryRunPlanner;
    this.itemExecution = itemExecution;
    this.primaryRunner = primaryRunner;
  }

  void execute(
      List<Phase> phases, ExecutionEventListener listener, CancellationSignal cancellation) {
    execute(phases, phases, listener, cancellation);
  }

  void execute(
      List<Phase> manifestPhases,
      List<Phase> executionPhases,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    Set<PhaseName> failedPhases = new HashSet<>();
    Set<PhaseName> unavailablePhases = new HashSet<>();
    Map<PhaseName, Phase> selected = selectedByName(executionPhases);
    for (Phase manifestPhase : planner.plan(manifestPhases)) {
      Phase phase = selected.get(manifestPhase.name());
      if (phase == null) {
        continue;
      }
      if (execute(phase, failedPhases, unavailablePhases, listener, cancellation) == Flow.STOP) {
        break;
      }
    }
    throwIfIncomplete(failedPhases, cancellation);
  }

  private Map<PhaseName, Phase> selectedByName(List<Phase> phases) {
    return phases.stream().collect(Collectors.toUnmodifiableMap(Phase::name, Function.identity()));
  }

  void preview(List<Phase> phases, ExecutionEventListener listener) {
    for (Phase phase : planner.plan(phases)) {
      preview(phase, listener);
    }
  }

  private Flow execute(
      Phase phase,
      Set<PhaseName> failedPhases,
      Set<PhaseName> unavailablePhases,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    if (cancellation.isCancelled()) {
      recordCancellation(phase, listener);
      return Flow.STOP;
    }
    if (isBlocked(phase, unavailablePhases)) {
      recordBlocked(phase, unavailablePhases, listener);
      unavailablePhases.add(phase.name());
      return Flow.CONTINUE;
    }
    return executeReadyPhase(phase, failedPhases, unavailablePhases, listener, cancellation);
  }

  private Flow executeReadyPhase(
      Phase phase,
      Set<PhaseName> failedPhases,
      Set<PhaseName> unavailablePhases,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    String fingerprint = fingerprintCalculator.fingerprint(phase);
    if (stateRecorder.isPhaseAlreadyCompleted(phase, fingerprint)) {
      emitCompletedPhase(phase, listener);
      return Flow.CONTINUE;
    }
    listener.onEvent(ExecutionEvent.phaseStarted(phase.name()));
    try {
      PhaseResult result = executeModules(phase, listener, cancellation);
      return finishPhase(phase, fingerprint, result, failedPhases, unavailablePhases, listener);
    } catch (ShellExecutionException e) {
      recordFailure(phase, fingerprint, failedPhases, unavailablePhases, listener);
      throw e;
    }
  }

  private PhaseResult executeModules(
      Phase phase, ExecutionEventListener listener, CancellationSignal cancellation) {
    List<BootstrapModule> modules = phase.modules();
    int startIndex = stateRecorder.resumeStartIndex(modules);
    ShellRunner shellRunner = selectShellRunner(phase.restartPolicy());
    boolean failed = false;
    for (int index = startIndex; index < modules.size(); index++) {
      BootstrapModule module = modules.get(index);
      if (cancellation.isCancelled()) {
        recordCancellation(phase, module.name(), listener);
        return PhaseResult.CANCELLED;
      }
      ModuleResult moduleResult =
          executeModule(
              module, nextModuleName(modules, index), shellRunner, listener, cancellation);
      if (moduleResult.stoppedAtBoundary()) {
        recordCancellation(phase, module.name(), listener);
        return PhaseResult.CANCELLED;
      }
      if (cancellation.isCancelled()) {
        ModuleName resumeAt = nextModuleName(modules, index).orElse(module.name());
        recordCancellation(phase, resumeAt, listener);
        return PhaseResult.CANCELLED;
      }
      if (moduleResult.failed() && !phase.continueOnModuleError()) {
        return PhaseResult.HARD_FAILURE;
      }
      failed |= moduleResult.failed();
    }
    return failed ? PhaseResult.HARD_FAILURE : PhaseResult.COMPLETED;
  }

  private ModuleResult executeModule(
      BootstrapModule module,
      Optional<ModuleName> followingModule,
      ShellRunner shellRunner,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    listener.onEvent(ExecutionEvent.moduleStarted(module.name()));
    try {
      if (module instanceof InterruptModule interrupt) {
        executeInterrupt(interrupt, followingModule, listener);
        return new ModuleResult(false, false);
      }
      return executeWithContext(module, shellRunner, listener, cancellation);
    } finally {
      listener.onEvent(ExecutionEvent.moduleCompleted(module.name()));
    }
  }

  private ModuleResult executeWithContext(
      BootstrapModule module,
      ShellRunner shellRunner,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    ExecutionCancellation.BoundaryResult<Boolean> result =
        ExecutionCancellation.withBoundary(
            cancellation,
            () ->
                ExecutionOutput.withSink(
                    itemExecution.outputSink(module.name(), module.name().value(), listener),
                    () -> moduleDispatcher.execute(module, listener, shellRunner, cancellation)));
    return new ModuleResult(result.value(), result.stoppedAtBoundary());
  }

  private Flow finishPhase(
      Phase phase,
      String fingerprint,
      PhaseResult result,
      Set<PhaseName> failedPhases,
      Set<PhaseName> unavailablePhases,
      ExecutionEventListener listener) {
    if (result == PhaseResult.CANCELLED) {
      return Flow.STOP;
    }
    if (result == PhaseResult.HARD_FAILURE) {
      recordFailure(phase, fingerprint, failedPhases, unavailablePhases, listener);
      return Flow.CONTINUE;
    }
    recordCompletion(phase, fingerprint, listener);
    return phase.restartPolicy() instanceof RestartPolicy.PromptLogout ? Flow.STOP : Flow.CONTINUE;
  }

  private void recordFailure(
      Phase phase,
      String fingerprint,
      Set<PhaseName> failedPhases,
      Set<PhaseName> unavailablePhases,
      ExecutionEventListener listener) {
    failedPhases.add(phase.name());
    unavailablePhases.add(phase.name());
    stateRecorder.recordPhase(
        phase.name(), PhaseStatus.FAILED, fingerprint, Optional.of(PHASE_FAILURE_REASON));
    listener.onEvent(ExecutionEvent.phaseFailed(phase.name()));
  }

  private void recordCompletion(Phase phase, String fingerprint, ExecutionEventListener listener) {
    stateRecorder.recordPhase(phase.name(), PhaseStatus.COMPLETED, fingerprint, Optional.empty());
    listener.onEvent(ExecutionEvent.phaseCompleted(phase.name()));
    if (phase.restartPolicy() instanceof RestartPolicy.PromptLogout prompt) {
      listener.onEvent(ExecutionEvent.restartRequired(phase.name(), prompt.message()));
    }
  }

  private void recordBlocked(
      Phase phase, Set<PhaseName> unavailablePhases, ExecutionEventListener listener) {
    String dependency = firstFailedDependency(phase, unavailablePhases);
    stateRecorder.recordPhase(
        phase.name(),
        PhaseStatus.BLOCKED,
        fingerprintCalculator.fingerprint(phase),
        Optional.of("Blocked by failed phase: " + dependency));
    listener.onEvent(ExecutionEvent.phaseBlocked(phase.name(), dependency));
  }

  private void preview(Phase phase, ExecutionEventListener listener) {
    if (stateRecorder.isPhaseAlreadyCompleted(phase, fingerprintCalculator.fingerprint(phase))) {
      emitCompletedPhase(phase, listener);
      return;
    }
    listener.onEvent(ExecutionEvent.phaseStarted(phase.name()));
    List<BootstrapModule> modules = phase.modules();
    for (int index = 0; index < modules.size(); index++) {
      preview(modules, index, selectShellRunner(phase.restartPolicy()), listener);
    }
    listener.onEvent(ExecutionEvent.phaseCompleted(phase.name()));
  }

  private void preview(
      List<BootstrapModule> modules,
      int index,
      ShellRunner shellRunner,
      ExecutionEventListener listener) {
    BootstrapModule module = modules.get(index);
    listener.onEvent(ExecutionEvent.moduleStarted(module.name()));
    if (module instanceof InterruptModule interrupt) {
      dryRunPlanner.previewInterrupt(interrupt, nextModuleName(modules, index), listener);
    } else {
      dryRunPlanner.preview(module, listener, shellRunner);
    }
    listener.onEvent(ExecutionEvent.moduleCompleted(module.name()));
  }

  private void executeInterrupt(
      InterruptModule module,
      Optional<ModuleName> followingModule,
      ExecutionEventListener listener) {
    String itemKey = module.name().value();
    Optional<String> nextEntry = RunStateRecorder.nextPlanEntry(module, followingModule);
    String message = interruptMessage(module, nextEntry);
    var result = new StepResult.Paused(itemKey, message, nextEntry, module.exitCode());
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), itemKey));
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), itemKey, result));
    stateRecorder.recordInterrupt(module, nextEntry);
    throw new ExecutionPausedException(itemKey, message, nextEntry, module.exitCode());
  }

  private void emitCompletedPhase(Phase phase, ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.phaseStarted(phase.name()));
    listener.onEvent(ExecutionEvent.phaseCompleted(phase.name()));
  }

  private boolean isBlocked(Phase phase, Set<PhaseName> failedPhases) {
    return phase.dependsOn().stream().anyMatch(failedPhases::contains);
  }

  private String firstFailedDependency(Phase phase, Set<PhaseName> failedPhases) {
    return phase.dependsOn().stream()
        .filter(failedPhases::contains)
        .map(PhaseName::value)
        .findFirst()
        .orElse("unknown");
  }

  private void throwIfIncomplete(Set<PhaseName> failedPhases, CancellationSignal cancellation) {
    if (cancellation.isCancelled()) {
      throw new ExecutionCancelledException();
    }
    if (!failedPhases.isEmpty()) {
      String names =
          failedPhases.stream().map(PhaseName::value).sorted().collect(Collectors.joining(", "));
      throw new BootstrapExecutionException("Bootstrap failed in phase(s): " + names);
    }
  }

  private ShellRunner selectShellRunner(RestartPolicy policy) {
    if (policy instanceof RestartPolicy.RequiresNewShell loginShell) {
      return new LoginShellWrappingRunner(primaryRunner, loginShell.shell());
    }
    return primaryRunner;
  }

  private Optional<ModuleName> nextModuleName(List<BootstrapModule> modules, int currentIndex) {
    return currentIndex + 1 < modules.size()
        ? Optional.of(modules.get(currentIndex + 1).name())
        : Optional.empty();
  }

  private String interruptMessage(InterruptModule module, Optional<String> nextEntry) {
    String resumeTarget =
        nextEntry.map(" Next plan entry: "::concat).orElse(" No next plan entry.");
    if (module.instructions().isEmpty()) {
      return module.message() + resumeTarget;
    }
    return module.message() + resumeTarget + " " + String.join(" ", module.instructions());
  }

  private void recordCancellation(Phase phase, ExecutionEventListener listener) {
    Optional<String> firstEntry =
        phase.modules().stream().findFirst().map(module -> module.name().value());
    stateRecorder.recordResumePoint(firstEntry);
    listener.onEvent(ExecutionEvent.cancelled(phase.name(), firstEntry));
  }

  private void recordCancellation(
      Phase phase, ModuleName nextModule, ExecutionEventListener listener) {
    Optional<String> nextEntry = Optional.of(nextModule.value());
    stateRecorder.recordResumePoint(nextEntry);
    listener.onEvent(ExecutionEvent.cancelled(phase.name(), nextEntry));
  }

  private enum Flow {
    CONTINUE,
    STOP
  }

  private enum PhaseResult {
    COMPLETED,
    HARD_FAILURE,
    CANCELLED
  }

  private record ModuleResult(boolean failed, boolean stoppedAtBoundary) {}
}
