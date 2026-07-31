package dev.sysboot.cli.command;

import dev.sysboot.executor.DetachedProcessTree;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class HostCommandRunner {

  private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);
  private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
  private final long timeoutNanos;
  private final int maxLines;

  HostCommandRunner(Duration timeout, int maxLines) {
    this.timeoutNanos = validatedNanos(timeout);
    if (maxLines < 0) {
      throw new IllegalArgumentException("maxLines must not be negative");
    }
    this.maxLines = maxLines;
  }

  boolean commandExists(String command) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return false;
    }
    return Arrays.stream(path.split(File.pathSeparator))
        .map(Path::of)
        .map(dir -> dir.resolve(command))
        .anyMatch(Files::isExecutable);
  }

  CommandResult lines(String... command) {
    if (!commandExists(command[0])) {
      return new CommandResult(false, List.of());
    }
    Process process;
    try {
      process =
          new ProcessBuilder(DetachedProcessTree.command(List.of(command)))
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      process.getOutputStream().close();
    } catch (IOException e) {
      return new CommandResult(false, List.of());
    }
    return collect(process);
  }

  private CommandResult collect(Process process) {
    List<String> output = Collections.synchronizedList(new ArrayList<>());
    Thread drain = startDrain(process, output);
    try {
      if (!process.waitFor(timeoutNanos, TimeUnit.NANOSECONDS)) {
        terminateTree(process);
        finishDrain(process, drain);
        return new CommandResult(false, List.of());
      }
      finishDrain(process, drain);
      return new CommandResult(process.exitValue() == 0, sorted(snapshot(output)));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      terminateTree(process);
      finishDrain(process, drain);
      return new CommandResult(false, List.of());
    }
  }

  private Thread startDrain(Process process, List<String> output) {
    return Thread.ofVirtual()
        .name("fluxion-host-command-output")
        .start(() -> drain(process, output));
  }

  private void drain(Process process, List<String> output) {
    try (Reader input = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
      var line = new StringBuilder();
      char[] buffer = new char[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        append(buffer, read, line, output);
      }
      addLine(line, output);
    } catch (IOException ignored) {
      // Closing the stream is how a timed-out drain is stopped.
    }
  }

  private void append(char[] buffer, int length, StringBuilder line, List<String> output) {
    for (int index = 0; index < length; index++) {
      char value = buffer[index];
      if (value == '\n' || value == '\r') {
        addLine(line, output);
        line.setLength(0);
      } else if (output.size() < maxLines && line.length() < 64 * 1024) {
        line.append(value);
      }
    }
  }

  private void addLine(StringBuilder line, List<String> output) {
    if (!line.isEmpty() && output.size() < maxLines) {
      output.add(line.toString());
    }
  }

  private void finishDrain(Process process, Thread drain) {
    if (join(drain, DRAIN_GRACE)) {
      return;
    }
    try {
      process.getInputStream().close();
    } catch (IOException ignored) {
      // The stream may already be closed after process exit.
    }
    join(drain, DRAIN_GRACE);
  }

  private boolean join(Thread thread, Duration limit) {
    try {
      thread.join(limit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return !thread.isAlive();
  }

  private void terminateTree(Process process) {
    DetachedProcessTree.terminate(process, TERMINATION_GRACE);
  }

  private List<String> snapshot(List<String> output) {
    synchronized (output) {
      return List.copyOf(output);
    }
  }

  private List<String> sorted(List<String> lines) {
    return lines.stream().map(String::strip).filter(line -> !line.isBlank()).sorted().toList();
  }

  record CommandResult(boolean success, List<String> lines) {
    CommandResult {
      lines = List.copyOf(lines);
    }
  }

  private static long validatedNanos(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    try {
      return timeout.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("timeout is too large", e);
    }
  }
}
