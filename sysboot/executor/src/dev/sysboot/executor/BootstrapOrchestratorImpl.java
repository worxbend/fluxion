package dev.sysboot.executor;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.ExecutionApproval;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.core.StepResult;
import java.util.List;
import java.util.Optional;

public final class BootstrapOrchestratorImpl implements BootstrapOrchestrator {

  private final RunStateRecorder stateRecorder;
  private final SourceSetupRunner sourceSetupRunner;
  private final PhaseExecutionRunner phaseRunner;
  private final ProfileStateLock applyLock;

  public BootstrapOrchestratorImpl(
      PackageManagerExecutorRegistry executorRegistry,
      ShellScriptExecutor shellScriptExecutor,
      CompiledBinaryInstaller binaryInstaller,
      AptRepositoryInstaller aptRepositoryInstaller,
      RpmRepositoryInstaller rpmRepositoryInstaller,
      PacmanRepositoryInstaller pacmanRepositoryInstaller,
      FileWriteExecutor fileWriteExecutor,
      FlatpakInstaller flatpakInstaller,
      FlatpakRemoteInstaller flatpakRemoteInstaller,
      DotbotExecutor dotbotExecutor,
      DefaultShellExecutor defaultShellExecutor,
      OhMyZshExecutor ohMyZshExecutor,
      ToolchainExecutor toolchainExecutor,
      NerdFontExecutor nerdFontExecutor,
      ShellReloadExecutor shellReloadExecutor,
      SkipEvaluator skipEvaluator,
      Optional<StateRepository> stateRepository,
      String profileName,
      ShellRunner primaryRunner,
      ExecutionApproval approval) {
    var moduleExecutors = moduleExecutors(executorRegistry, primaryRunner);
    var phaseExecutors =
        phaseExecutors(
            primaryRunner,
            shellScriptExecutor,
            dotbotExecutor,
            defaultShellExecutor,
            ohMyZshExecutor,
            toolchainExecutor,
            nerdFontExecutor,
            shellReloadExecutor,
            approval);
    var fingerprintCalculator = new PhaseFingerprintCalculator();
    this.stateRecorder =
        new RunStateRecorder(stateRepository, profileName, skipEvaluator, fingerprintCalculator);
    var itemExecution = new ItemExecution(skipEvaluator, stateRecorder);
    var sourceSetupExecutor =
        sourceSetupExecutor(
            aptRepositoryInstaller,
            rpmRepositoryInstaller,
            pacmanRepositoryInstaller,
            flatpakRemoteInstaller,
            primaryRunner);
    this.sourceSetupRunner = new SourceSetupRunner(sourceSetupExecutor, itemExecution);
    this.applyLock = new ProfileStateLock(new StatePaths());
    this.phaseRunner =
        phaseRunner(
            moduleExecutors,
            phaseExecutors,
            binaryInstaller,
            sourceSetupExecutor,
            fileWriteExecutor,
            flatpakInstaller,
            skipEvaluator,
            fingerprintCalculator,
            itemExecution,
            primaryRunner);
  }

  /** Backward-compatible constructor for focused executor tests. */
  public BootstrapOrchestratorImpl(
      PackageManagerExecutorRegistry executorRegistry,
      ShellScriptExecutor shellScriptExecutor,
      CompiledBinaryInstaller binaryInstaller,
      FlatpakInstaller flatpakInstaller,
      SkipEvaluator skipEvaluator,
      Optional<StateRepository> stateRepository,
      String profileName) {
    this(
        executorRegistry,
        shellScriptExecutor,
        binaryInstaller,
        new AptRepositoryInstaller(new DefaultShellRunner()),
        new RpmRepositoryInstaller(new DefaultShellRunner()),
        new PacmanRepositoryInstaller(new DefaultShellRunner()),
        new FileWriteExecutor(new DefaultShellRunner()),
        flatpakInstaller,
        new FlatpakRemoteInstaller(new DefaultShellRunner()),
        new DotbotExecutor(new DefaultShellRunner()),
        new DefaultShellExecutor(new DefaultShellRunner()),
        new OhMyZshExecutor(new DefaultShellRunner()),
        new ToolchainExecutor(new DefaultShellRunner()),
        new NerdFontExecutor(new DefaultShellRunner()),
        new ShellReloadExecutor(new DefaultShellRunner()),
        skipEvaluator,
        stateRepository,
        profileName,
        new DefaultShellRunner(),
        ExecutionApproval.denyAll());
  }

  @Override
  public void execute(BootstrapConfig config, ExecutionEventListener listener) {
    execute(config, listener, CancellationSignal.never());
  }

  @Override
  public void execute(
      BootstrapConfig config, ExecutionEventListener listener, CancellationSignal cancellation) {
    execute(config, config.phases(), listener, cancellation);
  }

  @Override
  public void execute(
      BootstrapConfig config,
      List<Phase> executionPhases,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    applyLock.withGlobalApplyLock(
        () -> {
          executeLocked(config, executionPhases, listener, cancellation);
          return null;
        });
  }

  private void executeLocked(
      BootstrapConfig config,
      List<Phase> executionPhases,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    stateRecorder.prepare(config);
    emitSkippedPlanEntries(config.skippedPlanEntries(), listener);
    boolean continueOnFailure = config.policy().continueOnErrorDefault().orElse(false);
    var sourceSetups = RelevantSourceSetups.select(config.sourceSetups(), executionPhases);
    SourceSetupRunner.Result sourceResult =
        sourceSetupRunner.execute(sourceSetups, continueOnFailure, listener, cancellation);
    if (sourceResult == SourceSetupRunner.Result.CANCELLED) {
      phaseRunner.execute(config.phases(), executionPhases, listener, cancellation);
      return;
    }
    if (sourceResult == SourceSetupRunner.Result.FAILED) {
      throw new BootstrapExecutionException("Bootstrap failed while configuring sources");
    }
    phaseRunner.execute(config.phases(), executionPhases, listener, cancellation);
  }

  @Override
  public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
    emitSkippedPlanEntries(config.skippedPlanEntries(), listener);
    sourceSetupRunner.preview(
        RelevantSourceSetups.select(config.sourceSetups(), config.phases()), listener);
    phaseRunner.preview(config.phases(), listener);
  }

  private ModuleExecutorRegistry moduleExecutors(
      PackageManagerExecutorRegistry executorRegistry, ShellRunner primaryRunner) {
    return new ModuleExecutorRegistry(
        List.of(
            new PackageModuleExecutor(executorRegistry), new SdkmanModuleExecutor(primaryRunner)));
  }

  private PhaseExecutors.Registry phaseExecutors(
      ShellRunner primaryRunner,
      ShellScriptExecutor shellScriptExecutor,
      DotbotExecutor dotbotExecutor,
      DefaultShellExecutor defaultShellExecutor,
      OhMyZshExecutor ohMyZshExecutor,
      ToolchainExecutor toolchainExecutor,
      NerdFontExecutor nerdFontExecutor,
      ShellReloadExecutor shellReloadExecutor,
      ExecutionApproval approval) {
    var executors =
        PhaseExecutors.injected(
            primaryRunner,
            shellScriptExecutor,
            dotbotExecutor,
            defaultShellExecutor,
            ohMyZshExecutor,
            toolchainExecutor,
            nerdFontExecutor,
            shellReloadExecutor,
            approval);
    return new PhaseExecutors.Registry(primaryRunner, executors, approval);
  }

  private SourceSetupExecutor sourceSetupExecutor(
      AptRepositoryInstaller aptRepositoryInstaller,
      RpmRepositoryInstaller rpmRepositoryInstaller,
      PacmanRepositoryInstaller pacmanRepositoryInstaller,
      FlatpakRemoteInstaller flatpakRemoteInstaller,
      ShellRunner primaryRunner) {
    return new SourceSetupExecutor(
        aptRepositoryInstaller,
        rpmRepositoryInstaller,
        pacmanRepositoryInstaller,
        new ZypperRepositoryInstaller(primaryRunner),
        flatpakRemoteInstaller,
        new VerifiedSourceArtifactDownloader());
  }

  private PhaseExecutionRunner phaseRunner(
      ModuleExecutorRegistry moduleExecutors,
      PhaseExecutors.Registry phaseExecutors,
      CompiledBinaryInstaller binaryInstaller,
      SourceSetupExecutor sourceSetupExecutor,
      FileWriteExecutor fileWriteExecutor,
      FlatpakInstaller flatpakInstaller,
      SkipEvaluator skipEvaluator,
      PhaseFingerprintCalculator fingerprintCalculator,
      ItemExecution itemExecution,
      ShellRunner primaryRunner) {
    var directExecutor =
        new DirectModuleExecutor(
            sourceSetupExecutor,
            fileWriteExecutor,
            flatpakInstaller,
            binaryInstaller,
            skipEvaluator,
            stateRecorder,
            itemExecution);
    var dispatcher =
        new ModuleDispatcher(
            moduleExecutors,
            phaseExecutors,
            directExecutor,
            itemExecution,
            skipEvaluator,
            stateRecorder);
    var dryRunPlanner =
        new DryRunPlanner(
            moduleExecutors,
            phaseExecutors,
            sourceSetupExecutor,
            fileWriteExecutor,
            binaryInstaller,
            itemExecution);
    return new PhaseExecutionRunner(
        new PhaseExecutionPlanner(),
        fingerprintCalculator,
        stateRecorder,
        dispatcher,
        dryRunPlanner,
        itemExecution,
        primaryRunner);
  }

  private void emitSkippedPlanEntries(
      List<SkippedPlanEntry> skippedEntries, ExecutionEventListener listener) {
    for (SkippedPlanEntry skipped : skippedEntries) {
      ModuleName moduleName = new ModuleName(skipped.name());
      listener.onEvent(ExecutionEvent.itemStarted(moduleName, skipped.name()));
      listener.onEvent(
          ExecutionEvent.itemCompleted(
              moduleName,
              skipped.name(),
              new StepResult.Skipped(skipped.name(), skipped.reason())));
    }
  }
}
