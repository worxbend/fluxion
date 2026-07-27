package dev.sysboot.core;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative stop request for a run.
 *
 * <p>Ctrl-C used to kill the JVM outright, leaving package-manager child processes orphaned and
 * state unwritten. The orchestrator instead checks this between items: the item in flight is
 * allowed to finish, state is flushed, and the user gets a resume hint. A second Ctrl-C still
 * escalates to an immediate exit, because a user pressing it twice means it.
 */
public final class CancellationSignal {

  private final AtomicBoolean cancelled = new AtomicBoolean();

  /** A signal that is never triggered, for callers that do not support cancellation. */
  public static CancellationSignal never() {
    return new CancellationSignal();
  }

  /**
   * Requests a stop.
   *
   * @return {@code true} the first time, {@code false} if a stop was already requested
   */
  public boolean cancel() {
    return cancelled.compareAndSet(false, true);
  }

  public boolean isCancelled() {
    return cancelled.get();
  }
}
