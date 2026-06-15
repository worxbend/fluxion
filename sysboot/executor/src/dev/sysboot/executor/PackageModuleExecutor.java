package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ZypperModule;
import java.util.List;

final class PackageModuleExecutor implements ModuleExecutor {

  private final PackageManagerExecutorRegistry executorRegistry;

  PackageModuleExecutor(PackageManagerExecutorRegistry executorRegistry) {
    this.executorRegistry = executorRegistry;
  }

  @Override
  public boolean supports(BootstrapModule module) {
    return module instanceof PackageModule || module instanceof ZypperModule;
  }

  @Override
  public List<ModuleItem> items(BootstrapModule module) {
    PackageModule packageModule = asPackageModule(module);
    return packageModule.packages().stream()
        .map(
            packageName ->
                ModuleItem.packageItem(
                    packageModule.name(), packageName.value(), packageModule.packageManager()))
        .toList();
  }

  @Override
  public boolean execute(
      BootstrapModule module, ExecutionEventListener listener, ModuleExecutionContext context) {
    PackageModule packageModule = asPackageModule(module);
    var executor = executorRegistry.forKind(packageModule.packageManager());
    boolean anyFailed = false;
    for (PackageName packageName : packageModule.packages()) {
      ModuleItem item =
          ModuleItem.packageItem(
              packageModule.name(), packageName.value(), packageModule.packageManager());
      listener.onEvent(ExecutionEvent.itemStarted(packageModule.name(), packageName.value()));
      SkipDecision decision = context.skipEvaluator().evaluate(item);
      if (decision instanceof SkipDecision.Skip skip) {
        listener.onEvent(
            ExecutionEvent.itemCompleted(
                packageModule.name(),
                packageName.value(),
                new StepResult.Skipped(packageName.value(), skip.reason().toString())));
        continue;
      }
      StepResult result = executor.install(packageName);
      listener.onEvent(
          ExecutionEvent.itemCompleted(packageModule.name(), packageName.value(), result));
      context
          .successRecorder()
          .record(packageModule.name(), packageName.value(), ItemType.PACKAGE, result);
      if (result instanceof StepResult.Failure) {
        if (!packageModule.continueOnError()) {
          return true;
        }
        anyFailed = true;
      }
    }
    return anyFailed;
  }

  @Override
  public void dryRun(BootstrapModule module, ExecutionEventListener listener) {
    PackageModule packageModule = asPackageModule(module);
    var executor = executorRegistry.forKind(packageModule.packageManager());
    packageModule
        .packages()
        .forEach(
            packageName ->
                emitDryRun(
                    packageModule,
                    packageName.value(),
                    executor.installCommand(packageName),
                    listener));
  }

  private PackageModule asPackageModule(BootstrapModule module) {
    return switch (module) {
      case PackageModule packageModule -> packageModule;
      case ZypperModule zypperModule -> zypperModule.asPackageModule();
      default -> throw new IllegalArgumentException("Unsupported package module: " + module);
    };
  }

  private void emitDryRun(
      PackageModule module, String item, List<String> command, ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), item));
    listener.onEvent(
        ExecutionEvent.itemCompleted(module.name(), item, new StepResult.DryRun(item, command)));
  }
}
