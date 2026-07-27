package dev.sysboot.core;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface ShellRunner {

  ProcessResult run(List<String> command, Map<String, String> env, Duration timeout);

  /**
   * Runs a command, delivering each output line to {@code outputSink} as it is produced.
   *
   * <p>Implementations that cannot stream fall back to the buffered form, so callers may always use
   * this overload. The sink is invoked from a background thread and must be thread-safe.
   */
  default ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Duration timeout,
      Consumer<String> outputSink) {
    return run(command, env, timeout);
  }
}
