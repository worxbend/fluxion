package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ExecutionPausedException;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.InterruptResumeMode;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.PlanEntryStateEntry;
import dev.sysboot.core.PlanEntryStatus;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class BootstrapOrchestratorImpl implements BootstrapOrchestrator {

  private final PackageManagerExecutorRegistry executorRegistry;
  private final ModuleExecutorRegistry moduleExecutorRegistry;
  private final ShellScriptExecutor shellScriptExecutor;
  private final CompiledBinaryInstaller binaryInstaller;
  private final AptRepositoryInstaller aptRepositoryInstaller;
  private final RpmRepositoryInstaller rpmRepositoryInstaller;
  private final PacmanRepositoryInstaller pacmanRepositoryInstaller;
  private final FlatpakInstaller flatpakInstaller;
  private final FlatpakRemoteInstaller flatpakRemoteInstaller;
  private final DotbotExecutor dotbotExecutor;
  private final DefaultShellExecutor defaultShellExecutor;
  private final OhMyZshExecutor ohMyZshExecutor;
  private final ToolchainExecutor toolchainExecutor;
  private final NerdFontExecutor nerdFontExecutor;
  private final ShellReloadExecutor shellReloadExecutor;
  private final SkipEvaluator skipEvaluator;
  private final Optional<StateRepository> stateRepository;
  private final String profileName;
  private final PhaseExecutionPlanner planner;
  private final PhaseFingerprintCalculator fingerprintCalculator;
  private final ShellRunner primaryRunner;
  private final DefaultShellRunner baseRunner;
  private final SourceSetupExecutor sourceSetupExecutor;
  private static final Duration CHECK_TIMEOUT = Duration.ofMinutes(5);

  public BootstrapOrchestratorImpl(
      PackageManagerExecutorRegistry executorRegistry,
      ShellScriptExecutor shellScriptExecutor,
      CompiledBinaryInstaller binaryInstaller,
      AptRepositoryInstaller aptRepositoryInstaller,
      RpmRepositoryInstaller rpmRepositoryInstaller,
      PacmanRepositoryInstaller pacmanRepositoryInstaller,
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
      DefaultShellRunner baseRunner) {
    this.executorRegistry = executorRegistry;
    this.moduleExecutorRegistry =
        new ModuleExecutorRegistry(List.of(new PackageModuleExecutor(executorRegistry)));
    this.shellScriptExecutor = shellScriptExecutor;
    this.binaryInstaller = binaryInstaller;
    this.aptRepositoryInstaller = aptRepositoryInstaller;
    this.rpmRepositoryInstaller = rpmRepositoryInstaller;
    this.pacmanRepositoryInstaller = pacmanRepositoryInstaller;
    this.flatpakInstaller = flatpakInstaller;
    this.flatpakRemoteInstaller = flatpakRemoteInstaller;
    this.dotbotExecutor = dotbotExecutor;
    this.defaultShellExecutor = defaultShellExecutor;
    this.ohMyZshExecutor = ohMyZshExecutor;
    this.toolchainExecutor = toolchainExecutor;
    this.nerdFontExecutor = nerdFontExecutor;
    this.shellReloadExecutor = shellReloadExecutor;
    this.skipEvaluator = skipEvaluator;
    this.stateRepository = stateRepository;
    this.profileName = profileName;
    this.planner = new PhaseExecutionPlanner();
    this.fingerprintCalculator = new PhaseFingerprintCalculator();
    this.primaryRunner = primaryRunner;
    this.baseRunner = baseRunner;
    this.sourceSetupExecutor =
        new SourceSetupExecutor(
            aptRepositoryInstaller,
            rpmRepositoryInstaller,
            pacmanRepositoryInstaller,
            new ZypperRepositoryInstaller(primaryRunner),
            flatpakRemoteInstaller);
  }

  /** Backward-compat constructor for tests (no new executors, no phases). */
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
        new DefaultShellRunner());
  }

  @Override
  public void execute(BootstrapConfig config, ExecutionEventListener listener) {
    prepareState(config);
    emitSkippedPlanEntries(config.skippedPlanEntries(), listener);
    if (executeSourceSetups(config, listener) == PhaseExecutionResult.HARD_FAILURE) {
      return;
    }
    List<Phase> ordered = planner.plan(config.phases());
    Set<PhaseName> failed = new HashSet<>();
    Set<PhaseName> blocked = new HashSet<>();

    for (Phase phase : ordered) {
      if (isBlocked(phase, failed)) {
        blocked.add(phase.name());
        String failedDependency = firstFailedDep(phase, failed);
        recordPhaseState(
            phase.name(),
            PhaseStatus.BLOCKED,
            fingerprintCalculator.fingerprint(phase),
            Optional.of("Blocked by failed phase: " + failedDependency));
        listener.onEvent(ExecutionEvent.phaseBlocked(phase.name(), failedDependency));
        continue;
      }

      String fingerprint = fingerprintCalculator.fingerprint(phase);
      if (shouldSkipPhase(phase, fingerprint)) {
        listener.onEvent(ExecutionEvent.phaseStarted(phase.name()));
        listener.onEvent(ExecutionEvent.phaseCompleted(phase.name()));
        continue;
      }

      listener.onEvent(ExecutionEvent.phaseStarted(phase.name()));
      ShellRunner phaseRunner = selectShellRunner(phase.restartPolicy());
      PhaseExecutionResult phaseResult = executePhase(phase, listener, phaseRunner);

      if (phaseResult == PhaseExecutionResult.HARD_FAILURE) {
        failed.add(phase.name());
        recordPhaseState(
            phase.name(),
            PhaseStatus.FAILED,
            fingerprint,
            Optional.of("Phase stopped after a module failure"));
        listener.onEvent(ExecutionEvent.phaseFailed(phase.name()));
      } else {
        recordPhaseState(phase.name(), PhaseStatus.COMPLETED, fingerprint, Optional.empty());
        listener.onEvent(ExecutionEvent.phaseCompleted(phase.name()));
        handleRestartPolicy(phase, listener);

        if (phase.restartPolicy() instanceof RestartPolicy.PromptLogout) {
          // State saved; exit gracefully — user must re-run after restart
          return;
        }
      }
    }
  }

  @Override
  public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
    emitSkippedPlanEntries(config.skippedPlanEntries(), listener);
    dryRunSourceSetups(config.sourceSetups(), listener);
    List<Phase> ordered = planner.plan(config.phases());
    for (Phase phase : ordered) {
      listener.onEvent(ExecutionEvent.phaseStarted(phase.name()));
      List<BootstrapModule> modules = phase.modules();
      for (int index = 0; index < modules.size(); index++) {
        BootstrapModule module = modules.get(index);
        listener.onEvent(ExecutionEvent.moduleStarted(module.name()));
        if (module instanceof InterruptModule interrupt) {
          dryRunInterrupt(interrupt, nextModuleName(modules, index), listener);
        } else {
          dryRunModule(module, listener);
        }
        listener.onEvent(ExecutionEvent.moduleCompleted(module.name()));
      }
      listener.onEvent(ExecutionEvent.phaseCompleted(phase.name()));
    }
  }

  private PhaseExecutionResult executePhase(
      Phase phase, ExecutionEventListener listener, ShellRunner phaseRunner) {
    List<BootstrapModule> modules = phase.modules();
    int startIndex = resumeStartIndex(modules);
    for (int index = startIndex; index < modules.size(); index++) {
      BootstrapModule module = modules.get(index);
      listener.onEvent(ExecutionEvent.moduleStarted(module.name()));
      boolean moduleFailed = false;
      try {
        if (module instanceof InterruptModule interrupt) {
          executeInterrupt(interrupt, nextModuleName(modules, index), listener);
        } else {
          moduleFailed = executeModule(module, listener, phaseRunner);
        }
      } finally {
        listener.onEvent(ExecutionEvent.moduleCompleted(module.name()));
      }
      if (moduleFailed && !phase.continueOnModuleError()) {
        return PhaseExecutionResult.HARD_FAILURE;
      }
    }
    return PhaseExecutionResult.COMPLETED;
  }

  private PhaseExecutionResult executeSourceSetups(
      BootstrapConfig config, ExecutionEventListener listener) {
    boolean continueOnFailure = config.policy().continueOnErrorDefault().orElse(false);
    for (SourceSetup setup : config.sourceSetups()) {
      boolean failed = executeSourceSetup(setup, listener);
      if (failed && !continueOnFailure) {
        return PhaseExecutionResult.HARD_FAILURE;
      }
    }
    return PhaseExecutionResult.COMPLETED;
  }

  private boolean executeSourceSetup(SourceSetup setup, ExecutionEventListener listener) {
    var item = sourceSetupExecutor.item(setup);
    listener.onEvent(ExecutionEvent.moduleStarted(setup.name()));
    listener.onEvent(ExecutionEvent.itemStarted(setup.name(), item.key()));
    SkipDecision decision = skipEvaluator.evaluate(item);
    if (decision instanceof SkipDecision.Skip skip) {
      emitSkipped(setup.name(), item.key(), skip, listener);
      listener.onEvent(ExecutionEvent.moduleCompleted(setup.name()));
      return false;
    }
    StepResult result = sourceSetupExecutor.execute(setup);
    listener.onEvent(ExecutionEvent.itemCompleted(setup.name(), item.key(), result));
    recordSuccess(setup.name(), item.key(), item.itemType(), result);
    listener.onEvent(ExecutionEvent.moduleCompleted(setup.name()));
    return result instanceof StepResult.Failure;
  }

  private void dryRunSourceSetups(
      List<SourceSetup> sourceSetups, ExecutionEventListener listener) {
    for (SourceSetup setup : sourceSetups) {
      var item = sourceSetupExecutor.item(setup);
      listener.onEvent(ExecutionEvent.moduleStarted(setup.name()));
      emitDryRun(setup.name(), item.key(), sourceSetupExecutor.commandPreview(setup), listener);
      listener.onEvent(ExecutionEvent.moduleCompleted(setup.name()));
    }
  }

  private boolean executeModule(
      BootstrapModule module, ExecutionEventListener listener, ShellRunner phaseRunner) {
    Optional<ModuleExecutor> moduleExecutor = moduleExecutorRegistry.find(module);
    if (moduleExecutor.isPresent()) {
      return moduleExecutor
          .orElseThrow()
          .execute(
              module, listener, new ModuleExecutionContext(skipEvaluator, this::recordSuccess));
    }
    return switch (module) {
      case AptRepositoryModule arm -> executeAptRepositoryModule(arm, listener);
      case RpmRepositoryModule rrm -> executeRpmRepositoryModule(rrm, listener);
      case PacmanRepositoryModule prm -> executePacmanRepositoryModule(prm, listener);
      case FlatpakModule fm -> executeFlatpakModule(fm, listener);
      case FlatpakRemoteModule frm -> executeFlatpakRemoteModule(frm, listener);
      case ShellScriptModule sm -> executeShellScript(sm, listener, phaseRunner);
      case CompiledBinaryModule bm -> executeBinaryInstall(bm, listener);
      case DotbotModule dm ->
          executeItem(
              dm.name(),
              dm.name().value(),
              ItemType.DOTBOT,
              () -> new DotbotExecutor(phaseRunner).execute(dm),
              listener);
      case DefaultShellModule dsm ->
          executeItem(
              dsm.name(),
              dsm.name().value(),
              ItemType.DEFAULT_SHELL,
              () -> new DefaultShellExecutor(phaseRunner).execute(dsm),
              listener);
      case OhMyZshModule omz ->
          executeItem(
              omz.name(),
              omz.name().value(),
              ItemType.OH_MY_ZSH,
              () -> new OhMyZshExecutor(phaseRunner).execute(omz),
              listener);
      case ToolchainModule tm ->
          executeItem(
              tm.name(),
              tm.name().value(),
              ItemType.TOOLCHAIN,
              () -> new ToolchainExecutor(phaseRunner).execute(tm),
              listener);
      case NerdFontModule nfm ->
          executeItem(
              nfm.name(),
              nfm.name().value(),
              ItemType.NERD_FONT,
              () -> new NerdFontExecutor(phaseRunner).execute(nfm),
              listener);
      case ShellReloadModule srm ->
          executeItem(
              srm.name(),
              srm.name().value(),
              ItemType.SHELL_RELOAD,
              () -> new ShellReloadExecutor(phaseRunner).execute(srm),
              listener);
      case ShellCommandModule sc ->
          executeItem(
              sc.name(),
              sc.name().value(),
              ItemType.SHELL_COMMAND,
              () -> new ShellCommandExecutor(phaseRunner).execute(sc),
              listener);
      case AssertModule am -> executeAssert(am, listener, phaseRunner);
      case ManualModule mm -> executeManual(mm, listener, phaseRunner);
      case InterruptModule ignored -> throw new IllegalStateException("Interrupt handled by phase");
      case PackageModule ignored -> throw new IllegalStateException("Package executor missing");
      case ZypperModule ignored -> throw new IllegalStateException("Zypper executor missing");
    };
  }

  private void executeInterrupt(
      InterruptModule module,
      Optional<ModuleName> followingModule,
      ExecutionEventListener listener) {
    String itemKey = module.name().value();
    Optional<String> nextEntry = nextPlanEntry(module, followingModule);
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), itemKey));
    var result =
        new StepResult.Paused(
            itemKey, interruptMessage(module, nextEntry), nextEntry, module.exitCode());
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), itemKey, result));
    recordInterrupt(module, nextEntry);
    throw new ExecutionPausedException(
        itemKey, interruptMessage(module, nextEntry), nextEntry, module.exitCode());
  }

  private boolean executeAssert(
      AssertModule module, ExecutionEventListener listener, ShellRunner phaseRunner) {
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), module.name().value()));
    ProcessResult result =
        runCheck(module.shell(), module.command(), module.workingDir(), phaseRunner);
    StepResult stepResult =
        result.isSuccess()
            ? new StepResult.Success(module.name().value(), result.elapsed())
            : new StepResult.Failure(
                module.name().value(), module.message(), result.exitCode(), result.elapsed());
    listener.onEvent(
        ExecutionEvent.itemCompleted(module.name(), module.name().value(), stepResult));
    recordSuccess(module.name(), module.name().value(), ItemType.ASSERT, stepResult);
    return stepResult instanceof StepResult.Failure;
  }

  private boolean executeManual(
      ManualModule module, ExecutionEventListener listener, ShellRunner phaseRunner) {
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), module.name().value()));
    SkipDecision decision = skipEvaluator.evaluate(module.name().value(), ItemType.MANUAL);
    if (decision instanceof SkipDecision.Skip skip) {
      emitSkipped(module.name(), module.name().value(), skip, listener);
      return false;
    }
    StepResult result = manualResult(module, phaseRunner);
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), module.name().value(), result));
    recordSuccess(module.name(), module.name().value(), ItemType.MANUAL, result);
    return result instanceof StepResult.Failure;
  }

  private StepResult manualResult(ManualModule module, ShellRunner phaseRunner) {
    if (module.probeCommand().isEmpty()) {
      return new StepResult.Failure(
          module.name().value(), "Manual step required: " + module.message(), 2, Duration.ZERO);
    }
    ProcessResult result =
        runCheck("/bin/bash", module.probeCommand().orElseThrow(), Optional.empty(), phaseRunner);
    if (result.isSuccess()) {
      return new StepResult.Success(module.name().value(), result.elapsed());
    }
    return new StepResult.Failure(
        module.name().value(), module.message(), result.exitCode(), result.elapsed());
  }

  private ProcessResult runCheck(
      String shell, String command, Optional<java.nio.file.Path> workingDir, ShellRunner runner) {
    Map<String, String> env =
        workingDir.map(path -> Map.of("PWD", path.toString())).orElse(Map.of());
    return runner.run(List.of(shell, "-lc", command), env, CHECK_TIMEOUT);
  }

  private boolean executeFlatpakModule(FlatpakModule module, ExecutionEventListener listener) {
    boolean anyFailed = false;
    for (String appId : module.appIds()) {
      listener.onEvent(ExecutionEvent.itemStarted(module.name(), appId));
      SkipDecision decision = skipEvaluator.evaluate(appId, ItemType.FLATPAK);
      if (decision instanceof SkipDecision.Skip skip) {
        listener.onEvent(
            ExecutionEvent.itemCompleted(
                module.name(), appId, new StepResult.Skipped(appId, skip.reason().toString())));
        continue;
      }
      StepResult result = flatpakInstaller.install(module, appId);
      listener.onEvent(ExecutionEvent.itemCompleted(module.name(), appId, result));
      recordSuccess(module.name(), appId, ItemType.FLATPAK, result);
      if (result instanceof StepResult.Failure) anyFailed = true;
    }
    return anyFailed && !module.continueOnError();
  }

  private boolean executeAptRepositoryModule(
      AptRepositoryModule module, ExecutionEventListener listener) {
    return executeItem(
        module.name(),
        module.sourceListPath().toString(),
        ItemType.APT_REPOSITORY,
        () -> aptRepositoryInstaller.add(module),
        listener);
  }

  private boolean executeRpmRepositoryModule(
      RpmRepositoryModule module, ExecutionEventListener listener) {
    return executeItem(
        module.name(),
        module.repoFilePath().toString(),
        ItemType.RPM_REPOSITORY,
        () -> rpmRepositoryInstaller.add(module),
        listener);
  }

  private boolean executePacmanRepositoryModule(
      PacmanRepositoryModule module, ExecutionEventListener listener) {
    return executeItem(
        module.name(),
        module.repositoryName(),
        ItemType.PACMAN_REPOSITORY,
        () -> pacmanRepositoryInstaller.add(module),
        listener);
  }

  private boolean executeFlatpakRemoteModule(
      FlatpakRemoteModule module, ExecutionEventListener listener) {
    return executeItem(
        module.name(),
        module.remote(),
        ItemType.FLATPAK_REMOTE,
        () -> flatpakRemoteInstaller.add(module),
        listener);
  }

  private boolean executeShellScript(
      ShellScriptModule module, ExecutionEventListener listener, ShellRunner phaseRunner) {
    String scriptKey = module.items().getFirst().key();
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), scriptKey));
    SkipDecision decision = skipEvaluator.evaluate(scriptKey, ItemType.SHELL_SCRIPT);
    if (decision instanceof SkipDecision.Skip skip) {
      listener.onEvent(
          ExecutionEvent.itemCompleted(
              module.name(),
              scriptKey,
              new StepResult.Skipped(scriptKey, skip.reason().toString())));
      return false;
    }
    StepResult result = new ShellScriptExecutor(phaseRunner).execute(module);
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), scriptKey, result));
    recordSuccess(module.name(), scriptKey, ItemType.SHELL_SCRIPT, result);
    return result instanceof StepResult.Failure && !module.continueOnError();
  }

  private boolean executeBinaryInstall(CompiledBinaryModule module, ExecutionEventListener listener) {
    String installKey = module.installPath().toString();
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), module.binaryName()));
    SkipDecision decision = skipEvaluator.evaluate(installKey, ItemType.COMPILED_BINARY);
    if (decision instanceof SkipDecision.Skip skip) {
      listener.onEvent(
          ExecutionEvent.itemCompleted(
              module.name(),
              module.binaryName(),
              new StepResult.Skipped(module.binaryName(), skip.reason().toString())));
      return false;
    }
    StepResult result = binaryInstaller.install(module);
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), module.binaryName(), result));
    recordBinarySuccess(module, installKey, result);
    return result instanceof StepResult.Failure && !module.continueOnError();
  }

  private boolean executeItem(
      ModuleName moduleName,
      String itemKey,
      ItemType itemType,
      java.util.function.Supplier<StepResult> action,
      ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(moduleName, itemKey));
    SkipDecision decision = skipEvaluator.evaluate(itemKey, itemType);
    if (decision instanceof SkipDecision.Skip skip) {
      listener.onEvent(
          ExecutionEvent.itemCompleted(
              moduleName, itemKey, new StepResult.Skipped(itemKey, skip.reason().toString())));
      return false;
    }
    StepResult result = action.get();
    listener.onEvent(ExecutionEvent.itemCompleted(moduleName, itemKey, result));
    recordSuccess(moduleName, itemKey, itemType, result);
    return result instanceof StepResult.Failure;
  }

  private void dryRunModule(BootstrapModule module, ExecutionEventListener listener) {
    Optional<ModuleExecutor> moduleExecutor = moduleExecutorRegistry.find(module);
    if (moduleExecutor.isPresent()) {
      moduleExecutor.orElseThrow().dryRun(module, listener);
      return;
    }
    switch (module) {
      case AptRepositoryModule arm ->
          emitDryRun(
              arm.name(),
              arm.sourceListPath().toString(),
              aptRepositoryInstaller.addCommand(arm),
              listener);
      case RpmRepositoryModule rrm ->
          emitDryRun(
              rrm.name(),
              rrm.repoFilePath().toString(),
              rpmRepositoryInstaller.addCommand(rrm),
              listener);
      case PacmanRepositoryModule prm ->
          emitDryRun(
              prm.name(),
              prm.repositoryName(),
              pacmanRepositoryInstaller.addCommand(prm),
              listener);
      case FlatpakModule fm ->
          fm.appIds()
              .forEach(
                  appId ->
                      emitDryRun(
                          fm.name(),
                          appId,
                          List.of("flatpak", "install", "-y", fm.remote(), appId),
                          listener));
      case FlatpakRemoteModule frm ->
          emitDryRun(frm.name(), frm.remote(), flatpakRemoteInstaller.addCommand(frm), listener);
      case ShellScriptModule sm ->
          sm.items()
              .forEach(
                  item ->
                      emitDryRun(
                          sm.name(),
                          item.name(),
                          new ShellScriptExecutor(new DefaultShellRunner()).commandPreview(item),
                          listener));
      case CompiledBinaryModule bm ->
          emitDryRun(bm.name(), bm.binaryName(), binaryInstaller.dryRunCommand(bm), listener);
      case DotbotModule dm ->
          emitDryRun(
              dm.name(),
              dm.config().toString(),
              List.of("dotbot-go", dm.installerVersion(), "--config", dm.config().toString()),
              listener);
      case DefaultShellModule dsm ->
          emitDryRun(
              dsm.name(),
              dsm.shellPath().toString(),
              List.of("chsh", "-s", dsm.shellPath().toString()),
              listener);
      case OhMyZshModule omz ->
          emitDryRun(omz.name(), "oh-my-zsh", List.of("sh", "<omz-installer>"), listener);
      case ToolchainModule tm ->
          emitDryRun(
              tm.name(), tm.kind().name().toLowerCase(), List.of("sh", "<installer>"), listener);
      case NerdFontModule nfm ->
          emitDryRun(
              nfm.name(),
              "nerd-fonts",
              List.of(nfm.nerdfontBinary(), "--config", "<config>"),
              listener);
      case ShellReloadModule srm ->
          emitDryRun(
              srm.name(),
              "shell-reload",
              List.of(srm.shell().binaryName(), "--login", "-i", "-c", "exit"),
              listener);
      case ShellCommandModule sc ->
          sc.items()
              .forEach(
                  item ->
                      emitDryRun(
                          sc.name(),
                          item.name(),
                          new ShellCommandExecutor(new DefaultShellRunner()).commandPreview(item),
                          listener));
      case AssertModule am ->
          emitDryRun(
              am.name(), am.name().value(), List.of(am.shell(), "-lc", am.command()), listener);
      case ManualModule mm ->
          emitDryRun(mm.name(), mm.name().value(), List.of("manual", mm.message()), listener);
      case InterruptModule ignored -> throw new IllegalStateException("Interrupt handled by phase");
      case PackageModule ignored -> throw new IllegalStateException("Package executor missing");
      case ZypperModule ignored -> throw new IllegalStateException("Zypper executor missing");
    }
  }

  private void dryRunInterrupt(
      InterruptModule module,
      Optional<ModuleName> followingModule,
      ExecutionEventListener listener) {
    Optional<String> nextEntry = nextPlanEntry(module, followingModule);
    emitDryRun(
        module.name(),
        module.name().value(),
        List.of(
            "interrupt",
            module.name().value(),
            "message=" + module.message(),
            "resumeFrom=" + module.resumeFrom().name().toLowerCase(),
            "exitCode=" + module.exitCode(),
            "state-write",
            "status=" + interruptStatus(module).name().toLowerCase(),
            "nextPlanEntry=" + nextEntry.orElse("<complete>")),
        listener);
  }

  private void emitSkipped(
      ModuleName moduleName,
      String itemKey,
      SkipDecision.Skip skip,
      ExecutionEventListener listener) {
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            moduleName, itemKey, new StepResult.Skipped(itemKey, skip.reason().toString())));
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

  private void emitDryRun(
      ModuleName module, String item, List<String> command, ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(module, item));
    listener.onEvent(
        ExecutionEvent.itemCompleted(module, item, new StepResult.DryRun(item, command)));
  }

  private boolean isBlocked(Phase phase, Set<PhaseName> failed) {
    return phase.dependsOn().stream().anyMatch(failed::contains);
  }

  private String firstFailedDep(Phase phase, Set<PhaseName> failed) {
    return phase.dependsOn().stream()
        .filter(failed::contains)
        .map(PhaseName::value)
        .findFirst()
        .orElse("unknown");
  }

  private boolean shouldSkipPhase(Phase phase, String fingerprint) {
    return stateRepository
        .flatMap(repo -> repo.load(profileName))
        .map(state -> state.isPhaseCompleted(phase.name().value(), fingerprint))
        .orElse(false);
  }

  private void prepareState(BootstrapConfig config) {
    stateRepository.ifPresent(
        repo -> {
          String identity = config.profileName().value();
          String fingerprint = fingerprintCalculator.manifestFingerprint(config);
          BootstrapState current =
              repo.load(profileName).orElse(BootstrapState.empty(profileName, "1.0.0"));
          rejectStaleState(current, identity, fingerprint);
          BootstrapState stamped = current.withManifestMetadata(identity, fingerprint);
          repo.save(stamped);
          skipEvaluator.refreshState(stamped);
        });
  }

  private void rejectStaleState(
      BootstrapState state, String identity, String fingerprint) {
    if (!state.hasRecordedWork()) {
      return;
    }
    if (state.manifestIdentity().filter(identity::equals).isEmpty()) {
      throw staleState("manifest identity");
    }
    if (state.manifestFingerprint().filter(fingerprint::equals).isEmpty()) {
      throw staleState("manifest fingerprint");
    }
  }

  private StaleStateException staleState(String reason) {
    return new StaleStateException(
        "Saved state is stale: "
            + reason
            + " differs. Reset state with `fluxion state reset "
            + profileName
            + " --force` or re-run apply with --reset-state.");
  }

  private int resumeStartIndex(List<BootstrapModule> modules) {
    Optional<String> nextEntry =
        stateRepository
            .flatMap(repo -> repo.load(profileName))
            .flatMap(state -> state.nextPlanEntry());
    if (nextEntry.isEmpty()) {
      return 0;
    }
    for (int index = 0; index < modules.size(); index++) {
      if (modules.get(index).name().value().equals(nextEntry.orElseThrow())) {
        clearNextPlanEntry();
        return index;
      }
    }
    return 0;
  }

  private void clearNextPlanEntry() {
    stateRepository.ifPresent(
        repo ->
            repo.load(profileName)
                .map(dev.sysboot.core.BootstrapState::withoutNextPlanEntry)
                .ifPresent(
                    updated -> {
                      repo.save(updated);
                      skipEvaluator.refreshState(updated);
                    }));
  }

  private ShellRunner selectShellRunner(RestartPolicy policy) {
    if (policy instanceof RestartPolicy.RequiresNewShell requiresNewShell) {
      return new LoginShellWrappingRunner(primaryRunner, requiresNewShell.shell());
    }
    return primaryRunner;
  }

  private void handleRestartPolicy(Phase phase, ExecutionEventListener listener) {
    if (phase.restartPolicy() instanceof RestartPolicy.PromptLogout pr) {
      listener.onEvent(ExecutionEvent.restartRequired(phase.name(), pr.message()));
    }
  }

  private void recordSuccess(
      ModuleName moduleName, String itemKey, ItemType itemType, StepResult result) {
    recordSuccess(moduleName, itemKey, itemType, result, Optional.empty());
  }

  private void recordBinarySuccess(CompiledBinaryModule module, String itemKey, StepResult result) {
    recordSuccess(
        module.name(),
        itemKey,
        ItemType.COMPILED_BINARY,
        result,
        Optional.of(module.url().toString()));
  }

  private void recordSuccess(
      ModuleName moduleName,
      String itemKey,
      ItemType itemType,
      StepResult result,
      Optional<String> sourceUrl) {
    if (!(result instanceof StepResult.Success success)) return;
    stateRepository.ifPresent(
        repo -> {
          var updatedState =
              repo.recordSuccess(
                  profileName,
                  new StateEntry(
                      profileName,
                      moduleName.value(),
                      itemKey,
                      itemType,
                      java.time.Instant.now(),
                      success.detectedVersion(),
                      success.checksum(),
                      sourceUrl));
          skipEvaluator.refreshState(updatedState);
        });
  }

  private void recordPhaseState(
      PhaseName phase, PhaseStatus status, String fingerprint, Optional<String> reason) {
    stateRepository.ifPresent(
        repo -> {
          var current =
              repo.load(profileName)
                  .orElse(dev.sysboot.core.BootstrapState.empty(profileName, "1.0.0"));
          var updated =
              current.withPhaseEntry(
                  new PhaseStateEntry(
                      phase.value(), status, Instant.now(), Optional.of(fingerprint), reason));
          repo.save(updated);
          skipEvaluator.refreshState(updated);
        });
  }

  private Optional<ModuleName> nextModuleName(List<BootstrapModule> modules, int currentIndex) {
    return currentIndex + 1 < modules.size()
        ? Optional.of(modules.get(currentIndex + 1).name())
        : Optional.empty();
  }

  private Optional<String> nextPlanEntry(
      InterruptModule module, Optional<ModuleName> followingModule) {
    if (module.resumeFrom() == InterruptResumeMode.CURRENT) {
      return Optional.of(module.name().value());
    }
    return followingModule.map(ModuleName::value);
  }

  private PlanEntryStatus interruptStatus(InterruptModule module) {
    return module.resumeFrom() == InterruptResumeMode.CURRENT
        ? PlanEntryStatus.INTERRUPTED
        : PlanEntryStatus.COMPLETED;
  }

  private void recordInterrupt(InterruptModule module, Optional<String> nextEntry) {
    stateRepository.ifPresent(
        repo -> {
          var current =
              repo.load(profileName)
                  .orElse(dev.sysboot.core.BootstrapState.empty(profileName, "1.0.0"));
          var updated =
              current
                  .withPlanEntry(
                      new PlanEntryStateEntry(
                          module.name().value(),
                          interruptStatus(module),
                          Instant.now(),
                          Optional.of(module.message())))
                  .withNextPlanEntry(nextEntry);
          repo.save(updated);
          skipEvaluator.refreshState(updated);
        });
  }

  private String interruptMessage(InterruptModule module, Optional<String> nextEntry) {
    String resumeTarget =
        nextEntry.map(" Next plan entry: "::concat).orElse(" No next plan entry.");
    if (module.instructions().isEmpty()) {
      return module.message() + resumeTarget;
    }
    return module.message() + resumeTarget + " " + String.join(" ", module.instructions());
  }

  private enum PhaseExecutionResult {
    COMPLETED,
    HARD_FAILURE
  }
}
