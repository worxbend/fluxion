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
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.UserGroupsModule;
import dev.sysboot.core.ZypperModule;
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
  private final PhaseExecutors executors;
  private final AptRepositoryInstaller aptRepositoryInstaller;
  private final RpmRepositoryInstaller rpmRepositoryInstaller;
  private final PacmanRepositoryInstaller pacmanRepositoryInstaller;
  private final FileWriteExecutor fileWriteExecutor;
  private final FlatpakRemoteInstaller flatpakRemoteInstaller;
  private final CompiledBinaryInstaller binaryInstaller;

  DryRunPlanner(
      ModuleExecutorRegistry moduleExecutors,
      PhaseExecutors executors,
      AptRepositoryInstaller aptRepositoryInstaller,
      RpmRepositoryInstaller rpmRepositoryInstaller,
      PacmanRepositoryInstaller pacmanRepositoryInstaller,
      FileWriteExecutor fileWriteExecutor,
      FlatpakRemoteInstaller flatpakRemoteInstaller,
      CompiledBinaryInstaller binaryInstaller) {
    this.moduleExecutors = moduleExecutors;
    this.executors = executors;
    this.aptRepositoryInstaller = aptRepositoryInstaller;
    this.rpmRepositoryInstaller = rpmRepositoryInstaller;
    this.pacmanRepositoryInstaller = pacmanRepositoryInstaller;
    this.fileWriteExecutor = fileWriteExecutor;
    this.flatpakRemoteInstaller = flatpakRemoteInstaller;
    this.binaryInstaller = binaryInstaller;
  }

  void preview(BootstrapModule module, ExecutionEventListener listener) {
    Optional<ModuleExecutor> moduleExecutor = moduleExecutors.find(module);
    if (moduleExecutor.isPresent()) {
      moduleExecutor.orElseThrow().dryRun(module, listener);
      return;
    }
    Optional<StepBinding> binding = StepBinding.find(module);
    if (binding.isPresent()) {
      StepBinding step = binding.orElseThrow();
      emitDryRun(
          module.name(), step.itemKey(module), step.commandPreview(module, executors), listener);
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
      case FileWriteModule fwm ->
          fwm.items()
              .forEach(
                  item ->
                      emitDryRun(
                          fwm.name(),
                          item.itemKey(),
                          fileWriteExecutor.dryRunCommand(item),
                          listener));
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
                          executors.shellScript().commandPreview(item),
                          listener));
      case CompiledBinaryModule bm ->
          emitDryRun(bm.name(), bm.binaryName(), binaryInstaller.dryRunCommand(bm), listener);
      case ShellCommandModule sc ->
          sc.items()
              .forEach(
                  item ->
                      emitDryRun(
                          sc.name(),
                          item.name(),
                          executors.shellCommand().commandPreview(item),
                          listener));
      case UserGroupsModule ugm ->
          emitDryRun(
              ugm.name(),
              ugm.itemKey(ugm.groups().getFirst()),
              executors.userGroups().commandPreview(ugm),
              listener);
      case AssertModule am ->
          emitDryRun(
              am.name(), am.name().value(), List.of(am.shell(), "-lc", am.command()), listener);
      case ManualModule mm ->
          emitDryRun(mm.name(), mm.name().value(), List.of("manual", mm.message()), listener);
      case InterruptModule ignored -> throw new IllegalStateException("Interrupt handled by phase");
      case SdkmanModule ignored -> throw new IllegalStateException("SDKMAN executor missing");
      case PackageModule ignored -> throw new IllegalStateException("Package executor missing");
      case ZypperModule ignored -> throw new IllegalStateException("Zypper executor missing");
      // Everything else is a StepBinding row, handled above.
      default ->
          throw new IllegalStateException("No preview for " + module.getClass().getSimpleName());
    }
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

  void emitDryRun(
      ModuleName module, String item, List<String> command, ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(module, item));
    listener.onEvent(
        ExecutionEvent.itemCompleted(module, item, new StepResult.DryRun(item, command)));
  }
}
