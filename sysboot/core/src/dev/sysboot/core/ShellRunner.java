package dev.sysboot.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface ShellRunner {

  ProcessResult run(List<String> command, Map<String, String> env, Duration timeout);

  /**
   * Runs a command, delivering each output line to {@code outputSink} as it is produced.
   *
   * <p>Implementations that cannot stream fall back to the buffered form, so callers may always use
   * this overload. Streaming implementations may invoke the sink from a background thread, so it
   * must be thread-safe.
   */
  default ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Duration timeout,
      Consumer<String> outputSink) {
    ProcessResult result = run(command, env, timeout);
    emitBuffered(result, outputSink);
    return result;
  }

  default ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Optional<Path> workingDirectory,
      Duration timeout) {
    return run(command, withPwd(env, workingDirectory), timeout);
  }

  default ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Optional<Path> workingDirectory,
      Duration timeout,
      Consumer<String> outputSink) {
    ProcessResult result = run(command, env, workingDirectory, timeout);
    emitBuffered(result, outputSink);
    return result;
  }

  private static Map<String, String> withPwd(
      Map<String, String> environment, Optional<Path> workingDirectory) {
    if (workingDirectory.isEmpty()) {
      return environment;
    }
    var values = new java.util.LinkedHashMap<>(environment);
    values.put("PWD", workingDirectory.orElseThrow().toString());
    return Map.copyOf(values);
  }

  private static void emitBuffered(ProcessResult result, Consumer<String> outputSink) {
    result.stdout().lines().forEach(outputSink);
    result.stderr().lines().forEach(outputSink);
  }
}
