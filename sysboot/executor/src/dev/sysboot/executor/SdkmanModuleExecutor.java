package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.SdkmanPackage;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;

final class SdkmanModuleExecutor implements ModuleExecutor {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);

  private final ShellRunner shellRunner;
  private final SensitiveTextRedactor redactor;

  SdkmanModuleExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
    this.redactor = new SensitiveTextRedactor();
  }

  @Override
  public boolean supports(BootstrapModule module) {
    return module instanceof SdkmanModule;
  }

  @Override
  public List<ModuleItem> items(BootstrapModule module) {
    SdkmanModule sdkmanModule = (SdkmanModule) module;
    return sdkmanModule.packages().stream()
        .map(pkg -> new ModuleItem(sdkmanModule.name(), pkg.itemKey(), ItemType.SDKMAN_PACKAGE))
        .toList();
  }

  @Override
  public boolean execute(
      BootstrapModule module, ExecutionEventListener listener, ModuleExecutionContext context) {
    SdkmanModule sdkmanModule = (SdkmanModule) module;
    boolean anyFailed = false;
    for (SdkmanPackage pkg : sdkmanModule.packages()) {
      if (context.cancellation().isCancelled()) {
        break;
      }
      StepResult result = executeItem(sdkmanModule, pkg, listener, context);
      anyFailed = anyFailed || result instanceof StepResult.Failure;
    }
    return anyFailed && !sdkmanModule.continueOnError();
  }

  @Override
  public void dryRun(
      BootstrapModule module, ExecutionEventListener listener, ShellRunner shellRunner) {
    dryRun(module, listener, shellRunner, SkipEvaluator.alwaysRun());
  }

  @Override
  public void dryRun(
      BootstrapModule module,
      ExecutionEventListener listener,
      ShellRunner shellRunner,
      SkipEvaluator skipEvaluator) {
    SdkmanModule sdkmanModule = (SdkmanModule) module;
    sdkmanModule
        .packages()
        .forEach(pkg -> emitDryRun(sdkmanModule, pkg, shellRunner, skipEvaluator, listener));
  }

  List<String> commandPreview(SdkmanPackage pkg) {
    return redactor.redactCommand(command(pkg), List.of());
  }

  private StepResult executeItem(
      SdkmanModule module,
      SdkmanPackage pkg,
      ExecutionEventListener listener,
      ModuleExecutionContext context) {
    ModuleItem item = new ModuleItem(module.name(), pkg.itemKey(), ItemType.SDKMAN_PACKAGE);
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), pkg.itemKey()));
    SkipDecision decision = context.skipEvaluator().evaluate(item);
    if (decision instanceof SkipDecision.Skip skip) {
      return skipped(module, pkg, skip, listener);
    }
    StepResult result = install(pkg, context.shellRunner().orElse(shellRunner));
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), pkg.itemKey(), result));
    context.successRecorder().record(module.name(), pkg.itemKey(), ItemType.SDKMAN_PACKAGE, result);
    return result;
  }

  private StepResult install(SdkmanPackage pkg, ShellRunner activeRunner) {
    ProcessResult result = activeRunner.run(command(pkg), Map.of(), INSTALL_TIMEOUT);
    if (result.exitCode() == 0) {
      return new StepResult.Success(pkg.itemKey(), result.elapsed());
    }
    return new StepResult.Failure(
        pkg.itemKey(),
        redactor.redact(result.stdout() + result.stderr(), List.of()),
        result.exitCode(),
        result.elapsed());
  }

  private StepResult skipped(
      SdkmanModule module,
      SdkmanPackage pkg,
      SkipDecision.Skip skip,
      ExecutionEventListener listener) {
    StepResult result = new StepResult.Skipped(pkg.itemKey(), skip.reason().toString());
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), pkg.itemKey(), result));
    return result;
  }

  private void emitDryRun(
      SdkmanModule module,
      SdkmanPackage pkg,
      ShellRunner activeRunner,
      SkipEvaluator skipEvaluator,
      ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), pkg.itemKey()));
    SkipDecision decision =
        skipEvaluator.evaluate(
            new ModuleItem(module.name(), pkg.itemKey(), ItemType.SDKMAN_PACKAGE));
    if (decision instanceof SkipDecision.Skip skip) {
      skipped(module, pkg, skip, listener);
      return;
    }
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            module.name(),
            pkg.itemKey(),
            new StepResult.DryRun(pkg.itemKey(), previewCommand(pkg, activeRunner))));
  }

  private List<String> previewCommand(SdkmanPackage pkg, ShellRunner activeRunner) {
    List<String> preview = commandPreview(pkg);
    if (activeRunner instanceof LoginShellWrappingRunner loginShell) {
      return loginShell.wrapCommand(preview);
    }
    return preview;
  }

  private List<String> command(SdkmanPackage pkg) {
    String install = "sdk install " + pkg.candidate() + versionArg(pkg);
    return List.of("/bin/bash", "-lc", "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && " + install);
  }

  private String versionArg(SdkmanPackage pkg) {
    return pkg.version().map(version -> " " + version).orElse("");
  }
}
