package dev.sysboot.core;

import java.util.List;

public interface BootstrapOrchestrator {

  void execute(BootstrapConfig config, ExecutionEventListener listener);

  /**
   * Runs the plan, stopping cleanly when {@code cancellation} is triggered.
   *
   * <p>Implementations that cannot be cancelled fall back to the uninterruptible form, so callers
   * may always pass a signal.
   */
  default void execute(
      BootstrapConfig config, ExecutionEventListener listener, CancellationSignal cancellation) {
    execute(config, listener);
  }

  /**
   * Executes selected phases while retaining the complete config as the persisted manifest
   * identity.
   */
  default void execute(
      BootstrapConfig config,
      List<Phase> executionPhases,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    if (!config.phases().equals(executionPhases)) {
      throw new UnsupportedOperationException("This orchestrator does not support phase selection");
    }
    execute(config, listener, cancellation);
  }

  void dryRun(BootstrapConfig config, ExecutionEventListener listener);
}
