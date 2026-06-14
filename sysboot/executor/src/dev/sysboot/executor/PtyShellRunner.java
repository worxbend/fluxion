package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PtyShellRunner implements ShellRunner {

  private static final Logger log = LoggerFactory.getLogger(PtyShellRunner.class);

  private final SudoPasswordProvider sudoPasswordProvider;

  public PtyShellRunner(SudoPasswordProvider sudoPasswordProvider) {
    this.sudoPasswordProvider = sudoPasswordProvider;
  }

  @Override
  public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
    log.debug("Interactive executing: {}", command.getFirst());
    Instant start = Instant.now();
    try {
      List<String> effectiveCommand = commandWithSudoStdin(command);
      ProcessBuilder builder = new ProcessBuilder(effectiveCommand);
      builder.redirectErrorStream(true);
      builder.environment().putAll(env);
      Process process = builder.start();
      writeSudoPasswordIfNeeded(command, process);
      boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (!finished) {
        process.destroyForcibly();
        return new ProcessResult(
            124, output, "Process timed out", Duration.between(start, Instant.now()));
      }
      return new ProcessResult(
          process.exitValue(), output, "", Duration.between(start, Instant.now()));
    } catch (IOException e) {
      throw new ShellExecutionException(
          "Interactive process failed to start: " + command.getFirst(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ShellExecutionException("Interactive process interrupted", e);
    }
  }

  private List<String> commandWithSudoStdin(List<String> command) {
    if (command.isEmpty() || !"sudo".equals(command.getFirst())) {
      return command;
    }
    List<String> updated = new ArrayList<>();
    updated.add("sudo");
    updated.add("-S");
    updated.add("-p");
    updated.add("[sudo] password:");
    updated.addAll(command.subList(1, command.size()));
    return List.copyOf(updated);
  }

  private void writeSudoPasswordIfNeeded(List<String> command, Process process) throws IOException {
    if (command.isEmpty() || !"sudo".equals(command.getFirst())) {
      return;
    }
    Optional<char[]> password = sudoPasswordProvider.requestPassword("[sudo] password");
    if (password.isEmpty()) {
      process.getOutputStream().write('\n');
      process.getOutputStream().flush();
      return;
    }
    char[] pwd = password.get();
    try {
      byte[] bytes = new String(pwd).getBytes(StandardCharsets.UTF_8);
      process.getOutputStream().write(bytes);
      process.getOutputStream().write('\n');
      process.getOutputStream().flush();
    } finally {
      Arrays.fill(pwd, '\0');
    }
  }
}
