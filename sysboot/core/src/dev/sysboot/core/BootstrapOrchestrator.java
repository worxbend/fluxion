package dev.sysboot.core;

public interface BootstrapOrchestrator {
  void execute(BootstrapConfig config, ExecutionEventListener listener);

  void dryRun(BootstrapConfig config, ExecutionEventListener listener);
}
