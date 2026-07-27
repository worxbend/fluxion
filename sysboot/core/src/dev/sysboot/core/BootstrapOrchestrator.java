package dev.sysboot.core;

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

  void dryRun(BootstrapConfig config, ExecutionEventListener listener);
}
