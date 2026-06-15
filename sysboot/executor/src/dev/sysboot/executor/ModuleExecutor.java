package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ModuleItem;
import java.util.List;

interface ModuleExecutor {

  boolean supports(BootstrapModule module);

  List<ModuleItem> items(BootstrapModule module);

  boolean execute(
      BootstrapModule module, ExecutionEventListener listener, ModuleExecutionContext context);

  void dryRun(BootstrapModule module, ExecutionEventListener listener);
}
