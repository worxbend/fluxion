package dev.sysboot.executor;

import dev.sysboot.core.ShellEnvironmentVariable;
import java.util.List;
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
  private static final ScopedValue<List<ShellEnvironmentVariable>> SENSITIVE_ENVIRONMENT =
      ScopedValue.newInstance();

  private ExecutionOutput() {}

  /** Runs {@code action} with command output routed to {@code sink}. */
  public static <T> T withSink(Consumer<String> sink, Supplier<T> action) {
    var guarded = new RedactingSink(sink, new SensitiveTextRedactor().streaming(List.of()));
    try {
      return ScopedValue.where(SINK, guarded).call(action::get);
    } finally {
      guarded.finish();
    }
  }

  /** Runs {@code action} with command output routed to {@code sink}. */
  public static void withSink(Consumer<String> sink, Runnable action) {
    var guarded = new RedactingSink(sink, new SensitiveTextRedactor().streaming(List.of()));
    try {
      ScopedValue.where(SINK, guarded).run(action);
    } finally {
      guarded.finish();
    }
  }

  /** Registers values that must be masked before a process line reaches the current output sink. */
  public static <T> T withSensitiveEnvironment(
      List<ShellEnvironmentVariable> environment, Supplier<T> action) {
    List<ShellEnvironmentVariable> sensitive =
        environment.stream().filter(ShellEnvironmentVariable::sensitive).toList();
    Consumer<String> current = sink();
    var guarded = new RedactingSink(current, new SensitiveTextRedactor().streaming(sensitive));
    try {
      return ScopedValue.where(SENSITIVE_ENVIRONMENT, sensitive)
          .where(SINK, guarded)
          .call(action::get);
    } finally {
      guarded.finish();
    }
  }

  /** The sink currently in scope, or a discarding one when nothing is listening. */
  public static Consumer<String> sink() {
    return SINK.orElse(DISCARD);
  }

  static List<ShellEnvironmentVariable> sensitiveEnvironment() {
    return SENSITIVE_ENVIRONMENT.orElse(List.of());
  }

  /** Whether anything is currently consuming live output. */
  public static boolean isBound() {
    return SINK.isBound();
  }

  private static final class RedactingSink implements Consumer<String> {

    private final Consumer<String> downstream;
    private final SensitiveTextRedactor.StreamingLineRedactor redactor;
    private boolean finished;

    private RedactingSink(
        Consumer<String> downstream, SensitiveTextRedactor.StreamingLineRedactor redactor) {
      this.downstream = downstream;
      this.redactor = redactor;
    }

    @Override
    public synchronized void accept(String line) {
      if (!finished) {
        emit(redactor.redact(line));
      }
    }

    private synchronized void finish() {
      if (!finished) {
        emit(redactor.finish());
        finished = true;
      }
    }

    private void emit(String text) {
      if (!text.isEmpty()) {
        downstream.accept(text);
      }
    }
  }
}
