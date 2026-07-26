package dev.sysboot.executor;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Carries the "where does live command output go" decision down to whichever shell runner ends up
 * executing a command.
 *
 * <p>A {@link ScopedValue} is used rather than an extra parameter on every executor because the
 * sink is ambient to a single item's execution and threading it through fifteen executor signatures
 * would obscure them for no benefit. The binding is read on the calling thread before any process
 * starts, so the output pump does not need to inherit it.
 */
public final class ExecutionOutput {

  private static final Consumer<String> DISCARD = line -> {};

  private static final ScopedValue<Consumer<String>> SINK = ScopedValue.newInstance();

  private ExecutionOutput() {}

  /** Runs {@code action} with command output routed to {@code sink}. */
  public static <T> T withSink(Consumer<String> sink, Supplier<T> action) {
    return ScopedValue.where(SINK, sink).call(action::get);
  }

  /** Runs {@code action} with command output routed to {@code sink}. */
  public static void withSink(Consumer<String> sink, Runnable action) {
    ScopedValue.where(SINK, sink).run(action);
  }

  /** The sink currently in scope, or a discarding one when nothing is listening. */
  public static Consumer<String> sink() {
    return SINK.orElse(DISCARD);
  }

  /** Whether anything is currently consuming live output. */
  public static boolean isBound() {
    return SINK.isBound();
  }
}
