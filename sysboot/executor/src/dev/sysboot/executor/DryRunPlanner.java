package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemSettingModule;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackagesModule;
import dev.sysboot.core.UserGroupsModule;
import dev.sysboot.core.ZypperModule;
import dev.sysboot.core.ZypperRepositoryModule;
import java.util.List;
import java.util.Optional;

/**
 * Renders what a run would do, without doing any of it.
 *
 * <p>Dry-run previously lived inside the orchestrator as a switch mirroring the execute switch,
 * which is the classic way for a preview to drift from the thing it claims to preview. Splitting it
 * out makes the duplication visible: every kind reachable through {@link StepBinding} is now
 * previewed from the same row that executes it, and only the genuinely bespoke shapes remain here.
 */
final class DryRunPlanner {

  private final ModuleExecutorRegistry moduleExecutors;
  private final PhaseExecutors.Registry executorRegistry;
  private final SourceSetupExecutor sourceSetupExecutor;
  private final FileWriteExecutor fileWriteExecutor;
  private final CompiledBinaryInstaller binaryInstaller;
  private final ItemExecution itemExecution;

  DryRunPlanner(
      ModuleExecutorRegistry moduleExecutors,
      PhaseExecutors.Registry executorRegistry,
      SourceSetupExecutor sourceSetupExecutor,
      FileWriteExecutor fileWriteExecutor,
      CompiledBinaryInstaller binaryInstaller,
      ItemExecution itemExecution) {
    this.moduleExecutors = moduleExecutors;
    this.executorRegistry = executorRegistry;
    this.sourceSetupExecutor = sourceSetupExecutor;
    this.fileWriteExecutor = fileWriteExecutor;
    this.binaryInstaller = binaryInstaller;
    this.itemExecution = itemExecution;
  }

  void preview(
      BootstrapModule module,
      ExecutionEventListener listener,
      dev.sysboot.core.ShellRunner shellRunner) {
    ExecutionEventListener output = transformCommands(module, listener, shellRunner);
    PhaseExecutors executors = executorRegistry.forRunner(shellRunner);
    Optional<ModuleExecutor> moduleExecutor = moduleExecutors.find(module);
    if (moduleExecutor.isPresent()) {
      moduleExecutor
          .orElseThrow()
          .dryRun(module, output, shellRunner, itemExecution.skipEvaluator());
      return;
    }
    Optional<StepBinding> binding = StepBinding.find(module);
    if (binding.isPresent()) {
      StepBinding step = binding.orElseThrow();
      itemExecution.preview(step.item(module), step.commandPreview(module, executors), output);
      return;
    }
    switch (module) {
      case AptRepositoryModule arm -> emitSourceSetup(arm.asSourceSetup(), output);
      case RpmRepositoryModule rrm -> emitSourceSetup(rrm.asSourceSetup(), output);
      case PacmanRepositoryModule prm -> emitSourceSetup(prm.asSourceSetup(), output);
      case FileWriteModule fwm ->
          fwm.items()
              .forEach(
                  item ->
                      preview(fwm, item.itemKey(), fileWriteExecutor.dryRunCommand(item), output));
      case FlatpakModule fm ->
          fm.appIds()
              .forEach(
                  appId ->
                      preview(
                          fm,
                          appId,
                          List.of("flatpak", "install", "-y", fm.remote(), appId),
                          output));
      case FlatpakRemoteModule frm -> emitSourceSetup(frm.asSourceSetup(), output);
      case ZypperRepositoryModule zrm -> emitSourceSetup(zrm.asSourceSetup(), output);
      case GpgKeyModule gkm ->
          gkm.keys()
              .forEach(
                  key ->
                      preview(gkm, key.itemKey(), executors.gpgKey().commandPreview(key), output));
      case ShellScriptModule sm ->
          sm.items()
              .forEach(
                  item ->
                      preview(
                          sm, item.name(), executors.shellScript().commandPreview(item), output));
      case CompiledBinaryModule bm ->
          preview(bm, bm.installPath().toString(), binaryInstaller.dryRunCommand(bm), output);
      case ShellCommandModule sc ->
          sc.items()
              .forEach(
                  item ->
                      preview(
                          sc, item.name(), executors.shellCommand().commandPreview(item), output));
      case UserGroupsModule ugm ->
          ugm.groups()
              .forEach(
                  group ->
                      preview(
                          ugm,
                          ugm.itemKey(group),
                          executors.userGroups().commandPreview(ugm, group),
                          output));
      case GitConfigModule gcm ->
          gcm.sortedKeys()
              .forEach(
                  key ->
                      preview(
                          gcm,
                          gcm.itemKey(key),
                          executors.gitConfig().commandPreview(gcm, key),
                          output));
      case GitRepoModule grm ->
          grm.repos()
              .forEach(
                  repo ->
                      preview(
                          grm,
                          repo.destination(),
                          executors.gitRepo().commandPreview(repo),
                          output));
      case SystemdUnitModule sum ->
          sum.units()
              .forEach(
                  unit ->
                      preview(
                          sum,
                          unit.qualifiedName(),
                          executors.systemdUnit().commandPreview(sum, unit),
                          output));
      case ToolPackagesModule tpm ->
          tpm.packages()
              .forEach(
                  pkg ->
                      preview(
                          tpm,
                          pkg.name(),
                          executors.toolPackages().commandPreview(tpm, pkg),
                          output));
      case SystemSettingModule ssm ->
          ssm.itemKeys()
              .forEach(
                  key ->
                      preview(
                          ssm, key, executors.systemSetting().commandPreview(ssm, key), output));
      case AssertModule am ->
          emitDryRun(
              am.name(), am.name().value(), List.of(am.shell(), "-lc", am.command()), output);
      case ManualModule mm ->
          preview(mm, mm.name().value(), List.of("manual", mm.message()), output);
      case InterruptModule ignored -> throw new IllegalStateException("Interrupt handled by phase");
      case SdkmanModule ignored -> throw new IllegalStateException("SDKMAN executor missing");
      case PackageModule ignored -> throw new IllegalStateException("Package executor missing");
      case ZypperModule ignored -> throw new IllegalStateException("Zypper executor missing");
      // Everything else is a StepBinding row, handled above.
      default ->
          throw new IllegalStateException("No preview for " + module.getClass().getSimpleName());
    }
  }

  private ExecutionEventListener transformCommands(
      BootstrapModule module,
      ExecutionEventListener listener,
      dev.sysboot.core.ShellRunner shellRunner) {
    if (!(shellRunner instanceof LoginShellWrappingRunner wrapper)
        || module instanceof ManualModule
        || module instanceof InterruptModule) {
      return listener;
    }
    return event -> listener.onEvent(transformCommand(event, wrapper));
  }

  private ExecutionEvent transformCommand(ExecutionEvent event, LoginShellWrappingRunner wrapper) {
    if (event.result().orElse(null) instanceof StepResult.DryRun dryRun) {
      return new ExecutionEvent(
          event.moduleName(),
          event.item(),
          event.kind(),
          Optional.of(
              new StepResult.DryRun(dryRun.item(), wrapper.wrapCommand(dryRun.wouldExecute()))),
          event.timestamp(),
          event.phaseContext(),
          event.outputLine());
    }
    return event;
  }

  void previewInterrupt(
      InterruptModule module,
      Optional<ModuleName> followingModule,
      ExecutionEventListener listener) {
    Optional<String> nextEntry = RunStateRecorder.nextPlanEntry(module, followingModule);
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
            "status=" + RunStateRecorder.interruptStatus(module).name().toLowerCase(),
            "nextPlanEntry=" + nextEntry.orElse("<complete>")),
        listener);
  }

  private void emitSourceSetup(SourceSetup setup, ExecutionEventListener listener) {
    ModuleItem item = sourceSetupExecutor.item(setup);
    itemExecution.preview(item, sourceSetupExecutor.commandPreview(setup), listener);
  }

  private void preview(
      BootstrapModule module,
      String itemKey,
      List<String> command,
      ExecutionEventListener listener) {
    ModuleItem item =
        ModuleItemCatalog.items(module).stream()
            .filter(candidate -> candidate.key().equals(itemKey))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Canonical item missing: " + itemKey));
    itemExecution.preview(item, command, listener);
  }

  void emitDryRun(
      ModuleName module, String item, List<String> command, ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(module, item));
    listener.onEvent(
        ExecutionEvent.itemCompleted(module, item, new StepResult.DryRun(item, command)));
  }
}
