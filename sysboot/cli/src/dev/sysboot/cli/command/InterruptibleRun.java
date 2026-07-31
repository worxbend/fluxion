package dev.sysboot.cli.command;

import dev.sysboot.core.CancellationSignal;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Turns Ctrl-C into a clean stop instead of an abrupt kill.
 *
 * <p>A shutdown hook is used rather than a signal handler because it needs no internal APIs and
 * behaves the same under GraalVM native image. On SIGINT the JVM starts shutting down and runs the
 * hook while the run thread keeps going: the hook raises the cancellation flag and then waits, so
 * the orchestrator can finish the item in flight, write state, and print a resume hint before the
 * process exits.
 *
 * <p>The hook waits on a latch signalled when the action returns, <em>not</em> on the run thread
 * terminating. Joining the thread deadlocks: the run thread is {@code main}, and {@code Main.main}
 * ends in {@code System.exit}, which blocks while a shutdown is already in progress. The hook would
 * then wait for a thread that is waiting for the hook, and every Ctrl-C would hang for the full
 * timeout before exiting.
 *
 * <p>A second Ctrl-C is not caught — the hook has already run, so the JVM terminates immediately.
 * That is deliberate: a user pressing it twice wants out now.
 */
final class InterruptibleRun {

  private static final Duration DRAIN_LIMIT = Duration.ofSeconds(30);
  private static final Duration TERMINATION_LIMIT = Duration.ofSeconds(5);

  private InterruptibleRun() {}

  /**
   * Runs {@code action} with a cancellation signal wired to SIGINT.
   *
   * @param onCancelRequested called once when a stop is first requested, for user-facing notice
   */
  static void run(Consumer<CancellationSignal> action, Runnable onCancelRequested) {
    var cancellation = new CancellationSignal();
    var finished = new CountDownLatch(1);
    Thread actionThread = Thread.currentThread();
    Thread hook =
        new Thread(
            () -> {
              if (cancellation.cancel()) {
                onCancelRequested.run();
              }
              awaitOrInterrupt(finished, actionThread, DRAIN_LIMIT);
            },
            "fluxion-cancel");
    Runtime.getRuntime().addShutdownHook(hook);
    try {
      action.accept(cancellation);
    } finally {
      finished.countDown();
      removeHook(hook);
    }
  }

  static void awaitOrInterrupt(CountDownLatch finished, Thread actionThread, Duration drainLimit) {
    if (awaitQuietly(finished, drainLimit)) {
      return;
    }
    actionThread.interrupt();
    awaitQuietly(finished, TERMINATION_LIMIT);
  }

  private static boolean awaitQuietly(CountDownLatch finished, Duration limit) {
    try {
      return finished.await(limit.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void removeHook(Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException ignored) {
      // Shutdown is already under way; the hook is running and must not be removed.
    }
  }
}
