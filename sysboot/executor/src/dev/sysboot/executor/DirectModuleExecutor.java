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
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemSettingModule;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackagesModule;
import dev.sysboot.core.UserGroupsModule;
import dev.sysboot.core.ZypperModule;
import dev.sysboot.core.ZypperRepositoryModule;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class DirectModuleExecutor {

  private static final Duration CHECK_TIMEOUT = Duration.ofMinutes(5);
  private static final int MANUAL_ACTION_REQUIRED_EXIT_CODE = 2;

  private final SourceSetupExecutor sourceSetupExecutor;
  private final FileWriteExecutor fileWriteExecutor;
  private final FlatpakInstaller flatpakInstaller;
  private final CompiledBinaryInstaller binaryInstaller;
  private final SkipEvaluator skipEvaluator;
  private final RunStateRecorder stateRecorder;
  private final ItemExecution itemExecution;
  private final ListModuleExecutor listModuleExecutor;

  DirectModuleExecutor(
      SourceSetupExecutor sourceSetupExecutor,
      FileWriteExecutor fileWriteExecutor,
      FlatpakInstaller flatpakInstaller,
      CompiledBinaryInstaller binaryInstaller,
      SkipEvaluator skipEvaluator,
      RunStateRecorder stateRecorder,
      ItemExecution itemExecution) {
    this.sourceSetupExecutor = sourceSetupExecutor;
    this.fileWriteExecutor = fileWriteExecutor;
    this.flatpakInstaller = flatpakInstaller;
    this.binaryInstaller = binaryInstaller;
    this.skipEvaluator = skipEvaluator;
    this.stateRecorder = stateRecorder;
    this.itemExecution = itemExecution;
    this.listModuleExecutor = new ListModuleExecutor(itemExecution);
  }

  boolean execute(
      BootstrapModule module,
      ExecutionEventListener listener,
      PhaseExecutors executors,
      ShellRunner shellRunner) {
    return switch (module) {
      case AptRepositoryModule repository -> execute(repository.asSourceSetup(), listener);
      case RpmRepositoryModule repository -> execute(repository.asSourceSetup(), listener);
      case PacmanRepositoryModule repository -> execute(repository.asSourceSetup(), listener);
      case FileWriteModule fileWrite -> execute(fileWrite, listener);
      case FlatpakModule flatpak -> execute(flatpak, listener);
      case FlatpakRemoteModule remote -> execute(remote.asSourceSetup(), listener);
      case ZypperRepositoryModule repository -> execute(repository.asSourceSetup(), listener);
      case GpgKeyModule gpgKey -> execute(gpgKey, listener, executors);
      case ShellScriptModule script -> execute(script, listener, executors);
      case CompiledBinaryModule binary -> execute(binary, listener);
      case ShellCommandModule command -> execute(command, listener, executors);
      case GitConfigModule gitConfig -> listModuleExecutor.execute(gitConfig, listener, executors);
      case GitRepoModule gitRepo -> listModuleExecutor.execute(gitRepo, listener, executors);
      case SystemdUnitModule systemd -> listModuleExecutor.execute(systemd, listener, executors);
      case ToolPackagesModule tools -> listModuleExecutor.execute(tools, listener, executors);
      case SystemSettingModule settings ->
          listModuleExecutor.execute(settings, listener, executors);
      case UserGroupsModule groups -> listModuleExecutor.execute(groups, listener, executors);
      case AssertModule assertion -> execute(assertion, listener, shellRunner);
      case ManualModule manual -> execute(manual, listener, shellRunner);
      case InterruptModule ignored -> throw missing("Interrupt handled by phase");
      case SdkmanModule ignored -> throw missing("SDKMAN executor missing");
      case PackageModule ignored -> throw missing("Package executor missing");
      case ZypperModule ignored -> throw missing("Zypper executor missing");
      default -> throw missing("No executor for " + module.getClass().getSimpleName());
    };
  }

  private boolean execute(SourceSetup setup, ExecutionEventListener listener) {
    ModuleItem item = sourceSetupExecutor.item(setup);
    return itemExecution.executeWithoutStreaming(
        item, () -> sourceSetupExecutor.execute(setup), listener);
  }

  private boolean execute(FileWriteModule module, ExecutionEventListener listener) {
    boolean anyFailed = false;
    for (var item : module.items()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      boolean failed =
          itemExecution.execute(
              module.name(),
              item.itemKey(),
              ItemType.FILE_WRITE,
              () -> fileWriteExecutor.write(item),
              listener);
      anyFailed |= failed;
      if (failed && !module.continueOnError()) {
        break;
      }
    }
    return anyFailed && !module.continueOnError();
  }

  private boolean execute(FlatpakModule module, ExecutionEventListener listener) {
    boolean anyFailed = false;
    for (String appId : module.appIds()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      var item = new ModuleItem(module.name(), appId, ItemType.FLATPAK);
      boolean failed =
          itemExecution.executeWithoutStreaming(
              item, () -> flatpakInstaller.install(module, appId), listener);
      anyFailed |= failed;
      if (failed && !module.continueOnError()) {
        break;
      }
    }
    return anyFailed && !module.continueOnError();
  }

  private boolean execute(
      GpgKeyModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    boolean anyFailed = false;
    for (var key : module.keys()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      boolean failed =
          itemExecution.execute(
              module.name(),
              key.itemKey(),
              ItemType.GPG_KEY,
              () -> executors.gpgKey().executeItem(key),
              listener);
      anyFailed |= failed;
      if (failed && !module.continueOnError()) {
        break;
      }
    }
    return anyFailed;
  }

  private boolean execute(
      ShellScriptModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    boolean anyFailed = false;
    for (var script : module.items()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      var item = new ModuleItem(module.name(), script.name(), ItemType.SHELL_SCRIPT);
      boolean failed =
          itemExecution.execute(
              item.moduleName(),
              item.key(),
              item.itemType(),
              () -> executeScript(script, executors),
              listener);
      anyFailed |= failed;
      if (failed && !module.continueOnError()) {
        return true;
      }
    }
    return anyFailed && !module.continueOnError();
  }

  private boolean execute(CompiledBinaryModule module, ExecutionEventListener listener) {
    String stateKey = module.installPath().toString();
    var item = ModuleItemCatalog.items(module).getFirst();
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), item.key()));
    SkipDecision decision = skipEvaluator.evaluate(item);
    if (decision instanceof SkipDecision.Skip skip) {
      emitBinarySkipped(module, skip, listener);
      return false;
    }
    StepResult result = binaryInstaller.install(module);
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), item.key(), result));
    stateRecorder.recordBinarySuccess(module, stateKey, result);
    return result instanceof StepResult.Failure;
  }

  private boolean execute(
      ShellCommandModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    boolean anyFailed = false;
    for (var command : module.items()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      boolean failed =
          itemExecution.execute(
              module.name(),
              command.name(),
              ItemType.SHELL_COMMAND,
              () -> executeCommand(command, executors),
              listener);
      anyFailed |= failed;
      if (failed && !module.continueOnError()) {
        return true;
      }
    }
    return anyFailed && !module.continueOnError();
  }

  private StepResult executeScript(
      dev.sysboot.core.ShellScriptItem script, PhaseExecutors executors) {
    if (script.confirm().isPresent()) {
      return executors.shellScript().executeItem(script, executors.approval());
    }
    return executors.shellScript().executeItem(script);
  }

  private StepResult executeCommand(
      dev.sysboot.core.ShellCommandItem command, PhaseExecutors executors) {
    if (command.confirm().isPresent()) {
      return executors.shellCommand().executeItem(command, executors.approval());
    }
    return executors.shellCommand().executeItem(command);
  }

  private boolean execute(
      AssertModule module, ExecutionEventListener listener, ShellRunner shellRunner) {
    String itemKey = module.name().value();
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), itemKey));
    ProcessResult process =
        runCheck(module.shell(), module.command(), module.workingDir(), shellRunner);
    StepResult result = assertionResult(module, process);
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), itemKey, result));
    stateRecorder.recordSuccess(module.name(), itemKey, ItemType.ASSERT, result);
    return result instanceof StepResult.Failure;
  }

  private boolean execute(
      ManualModule module, ExecutionEventListener listener, ShellRunner shellRunner) {
    var item = new ModuleItem(module.name(), module.name().value(), ItemType.MANUAL);
    return itemExecution.executeWithoutStreaming(
        item, () -> manualResult(module, shellRunner), listener);
  }

  private StepResult assertionResult(AssertModule module, ProcessResult process) {
    if (process.isSuccess()) {
      return new StepResult.Success(module.name().value(), process.elapsed());
    }
    return new StepResult.Failure(
        module.name().value(), module.message(), process.exitCode(), process.elapsed());
  }

  private StepResult manualResult(ManualModule module, ShellRunner shellRunner) {
    if (module.probeCommand().isEmpty()) {
      return new StepResult.Failure(
          module.name().value(),
          "Manual step required: " + module.message(),
          MANUAL_ACTION_REQUIRED_EXIT_CODE,
          Duration.ZERO);
    }
    ProcessResult process =
        runCheck("/bin/bash", module.probeCommand().orElseThrow(), Optional.empty(), shellRunner);
    if (process.isSuccess()) {
      return new StepResult.Success(module.name().value(), process.elapsed());
    }
    return new StepResult.Failure(
        module.name().value(), module.message(), process.exitCode(), process.elapsed());
  }

  private ProcessResult runCheck(
      String shell, String command, Optional<java.nio.file.Path> workingDir, ShellRunner runner) {
    return runner.run(List.of(shell, "-lc", command), Map.of(), workingDir, CHECK_TIMEOUT);
  }

  private void emitBinarySkipped(
      CompiledBinaryModule module, SkipDecision.Skip skip, ExecutionEventListener listener) {
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            module.name(),
            module.installPath().toString(),
            new StepResult.Skipped(module.installPath().toString(), skip.reason().toString())));
  }

  private IllegalStateException missing(String message) {
    return new IllegalStateException(message);
  }
}
