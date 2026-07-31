package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ShellRunner;
import java.util.List;

interface ModuleExecutor {

  boolean supports(BootstrapModule module);

  List<ModuleItem> items(BootstrapModule module);

  boolean execute(
      BootstrapModule module, ExecutionEventListener listener, ModuleExecutionContext context);

  void dryRun(BootstrapModule module, ExecutionEventListener listener, ShellRunner shellRunner);

  default void dryRun(
      BootstrapModule module,
      ExecutionEventListener listener,
      ShellRunner shellRunner,
      SkipEvaluator skipEvaluator) {
    dryRun(module, listener, shellRunner);
  }

  default void dryRun(BootstrapModule module, ExecutionEventListener listener) {
    dryRun(
        module,
        listener,
        (command, environment, timeout) -> {
          throw new IllegalStateException("dry-run preview must not execute commands");
        });
  }
}
