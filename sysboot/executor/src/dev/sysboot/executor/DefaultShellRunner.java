package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultShellRunner implements ShellRunner {

  private static final Logger log = LoggerFactory.getLogger(DefaultShellRunner.class);
  private final SensitiveTextRedactor redactor = new SensitiveTextRedactor();

  @Override
  public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
    log.debug("Executing: {}", maskSensitive(command));
    Instant start = Instant.now();
    try {
      ProcessBuilder builder = new ProcessBuilder(command);
      builder.redirectErrorStream(true);
      builder.environment().putAll(env);

      Process process = builder.start();
      String output = new String(process.getInputStream().readAllBytes());

      boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        Duration elapsed = Duration.between(start, Instant.now());
        return new ProcessResult(124, output, "Process timed out after " + timeout, elapsed);
      }

      Duration elapsed = Duration.between(start, Instant.now());
      return new ProcessResult(process.exitValue(), output, "", elapsed);
    } catch (IOException e) {
      throw new ShellExecutionException("Failed to start process: " + command.getFirst(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ShellExecutionException("Process interrupted: " + command.getFirst(), e);
    }
  }

  private List<String> maskSensitive(List<String> command) {
    return command.stream()
        .map(arg -> redactor.redact(arg, List.of()))
        .map(this::truncate)
        .toList();
  }

  private String truncate(String argument) {
    return argument.length() > 60 ? argument.substring(0, 57) + "..." : argument;
  }
}
