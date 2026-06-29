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
      StepResult result = executeItem(sdkmanModule, pkg, listener, context);
      anyFailed = anyFailed || result instanceof StepResult.Failure;
    }
    return anyFailed && !sdkmanModule.continueOnError();
  }

  @Override
  public void dryRun(BootstrapModule module, ExecutionEventListener listener) {
    SdkmanModule sdkmanModule = (SdkmanModule) module;
    sdkmanModule.packages().forEach(pkg -> emitDryRun(sdkmanModule, pkg, listener));
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
    StepResult result = install(pkg);
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), pkg.itemKey(), result));
    context.successRecorder().record(module.name(), pkg.itemKey(), ItemType.SDKMAN_PACKAGE, result);
    return result;
  }

  private StepResult install(SdkmanPackage pkg) {
    ProcessResult result = shellRunner.run(command(pkg), Map.of(), INSTALL_TIMEOUT);
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
      SdkmanModule module, SdkmanPackage pkg, ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), pkg.itemKey()));
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            module.name(),
            pkg.itemKey(),
            new StepResult.DryRun(pkg.itemKey(), commandPreview(pkg))));
  }

  private List<String> command(SdkmanPackage pkg) {
    String install = "sdk install " + pkg.candidate() + versionArg(pkg);
    return List.of(
        "/bin/bash", "-lc", "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && " + install);
  }

  private String versionArg(SdkmanPackage pkg) {
    return pkg.version().map(version -> " " + version).orElse("");
  }
}
