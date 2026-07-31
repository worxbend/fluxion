package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.PackageManagerAction;
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
    var items = new java.util.ArrayList<ModuleItem>();
    for (int index = 0; index < packageModule.actions().size(); index++) {
      PackageManagerAction action = packageModule.actions().get(index);
      items.add(
          ModuleItem.packageActionItem(
              packageModule.name(), action.itemKey(index), action, packageModule.packageManager()));
    }
    packageModule.packages().stream()
        .map(
            packageName ->
                ModuleItem.packageItem(
                    packageModule.name(), packageName.value(), packageModule.packageManager()))
        .forEach(items::add);
    return List.copyOf(items);
  }

  @Override
  public boolean execute(
      BootstrapModule module, ExecutionEventListener listener, ModuleExecutionContext context) {
    PackageModule packageModule = asPackageModule(module);
    var executor = executorRegistry.forKind(packageModule.packageManager());
    boolean anyFailed = false;
    for (int index = 0; index < packageModule.actions().size(); index++) {
      if (context.cancellation().isCancelled()) {
        break;
      }
      PackageManagerAction action = packageModule.actions().get(index);
      StepResult result = executeAction(packageModule, action, index, executor, listener, context);
      if (result instanceof StepResult.Failure) {
        anyFailed = true;
      }
    }
    for (PackageName packageName : packageModule.packages()) {
      if (context.cancellation().isCancelled()) {
        break;
      }
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
        anyFailed = true;
      }
    }
    return anyFailed && !packageModule.continueOnError();
  }

  @Override
  public void dryRun(
      BootstrapModule module,
      ExecutionEventListener listener,
      dev.sysboot.core.ShellRunner shellRunner) {
    dryRun(module, listener, shellRunner, SkipEvaluator.alwaysRun());
  }

  @Override
  public void dryRun(
      BootstrapModule module,
      ExecutionEventListener listener,
      dev.sysboot.core.ShellRunner shellRunner,
      SkipEvaluator skipEvaluator) {
    PackageModule packageModule = asPackageModule(module);
    var executor = executorRegistry.forKind(packageModule.packageManager());
    for (int index = 0; index < packageModule.actions().size(); index++) {
      PackageManagerAction action = packageModule.actions().get(index);
      emitDryRun(
          ModuleItem.packageActionItem(
              packageModule.name(), action.itemKey(index), action, packageModule.packageManager()),
          executor.actionCommand(action),
          skipEvaluator,
          listener);
    }
    packageModule
        .packages()
        .forEach(
            packageName ->
                emitDryRun(
                    ModuleItem.packageItem(
                        packageModule.name(), packageName.value(), packageModule.packageManager()),
                    executor.installCommand(packageName),
                    skipEvaluator,
                    listener));
  }

  private PackageModule asPackageModule(BootstrapModule module) {
    return switch (module) {
      case PackageModule packageModule -> packageModule;
      case ZypperModule zypperModule -> zypperModule.asPackageModule();
      default -> throw new IllegalArgumentException("Unsupported package module: " + module);
    };
  }

  private StepResult executeAction(
      PackageModule module,
      PackageManagerAction action,
      int index,
      dev.sysboot.core.PackageManagerExecutor executor,
      ExecutionEventListener listener,
      ModuleExecutionContext context) {
    ModuleItem item =
        ModuleItem.packageActionItem(
            module.name(), action.itemKey(index), action, module.packageManager());
    listener.onEvent(ExecutionEvent.itemStarted(module.name(), item.key()));
    SkipDecision decision = context.skipEvaluator().evaluate(item);
    if (decision instanceof SkipDecision.Skip skip) {
      StepResult result = new StepResult.Skipped(item.key(), skip.reason().toString());
      listener.onEvent(ExecutionEvent.itemCompleted(module.name(), item.key(), result));
      return result;
    }
    StepResult result = executor.runAction(action);
    listener.onEvent(ExecutionEvent.itemCompleted(module.name(), item.key(), result));
    context.successRecorder().record(module.name(), item.key(), ItemType.PACKAGE_ACTION, result);
    return result;
  }

  private void emitDryRun(
      ModuleItem item,
      List<String> command,
      SkipEvaluator skipEvaluator,
      ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(item.moduleName(), item.key()));
    SkipDecision decision = skipEvaluator.evaluate(item);
    if (decision instanceof SkipDecision.Skip skip) {
      listener.onEvent(
          ExecutionEvent.itemCompleted(
              item.moduleName(),
              item.key(),
              new StepResult.Skipped(item.key(), skip.reason().toString())));
      return;
    }
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            item.moduleName(), item.key(), new StepResult.DryRun(item.key(), command)));
  }
}
