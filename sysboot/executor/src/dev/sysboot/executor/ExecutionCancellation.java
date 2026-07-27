package dev.sysboot.executor;

import dev.sysboot.core.CancellationSignal;
import java.util.function.Supplier;

/**
 * Makes the run's cancellation signal reachable from inside a module's own item loop.
 *
 * <p>Checking only between modules meant Ctrl-C during a seventy-app Flatpak step or a
 * sixty-package install did nothing until that whole module finished — which is precisely the
 * situation where someone reaches for Ctrl-C. Like {@link ExecutionOutput}, this is ambient to one
 * run rather than a parameter on fifteen executor signatures.
 */
public final class ExecutionCancellation {

  private static final ScopedValue<CancellationSignal> SIGNAL = ScopedValue.newInstance();

  private ExecutionCancellation() {}

  static <T> T with(CancellationSignal signal, Supplier<T> action) {
    return ScopedValue.where(SIGNAL, signal).call(action::get);
  }

  /** True when the run has been asked to stop and the current loop should break. */
  public static boolean isCancelled() {
    return SIGNAL.isBound() && SIGNAL.get().isCancelled();
  }
}
