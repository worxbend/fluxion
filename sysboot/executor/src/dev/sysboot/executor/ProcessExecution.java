package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Single implementation of "run a child process safely".
 *
 * <p>Output is drained on a separate thread while the parent waits for exit. Draining and waiting
 * must overlap: reading to EOF first makes the timeout unreachable, and waiting first deadlocks as
 * soon as the child fills the pipe buffer. Timeouts terminate the whole process tree rather than
 * only the direct child, because package managers and installer scripts spawn helpers.
 */
final class ProcessExecution {

  static final int TIMEOUT_EXIT_CODE = 124;

  private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);
  private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);

  private ProcessExecution() {}

  record Request(
      List<String> command,
      Map<String, String> environment,
      Optional<Path> workingDirectory,
      Optional<byte[]> stdin,
      Duration timeout,
      Consumer<String> outputSink) {

    Request {
      command = List.copyOf(command);
      environment = Map.copyOf(environment);
    }

    static Request of(List<String> command, Map<String, String> environment, Duration timeout) {
      return new Request(
          command, environment, Optional.empty(), Optional.empty(), timeout, line -> {});
    }

    Request withStdin(byte[] bytes) {
      return new Request(
          command, environment, workingDirectory, Optional.of(bytes), timeout, outputSink);
    }

    Request withOutputSink(Consumer<String> sink) {
      return new Request(command, environment, workingDirectory, stdin, timeout, sink);
    }
  }

  static ProcessResult run(Request request) {
    Instant start = Instant.now();
    Process process = start(request);
    var capture = new BoundedTextCapture();
    Thread pump = startOutputPump(process, capture, request.outputSink());
    writeStdin(process, request.stdin());
    try {
      boolean exited = awaitExit(process, request.timeout());
      joinPump(pump);
      Duration elapsed = Duration.between(start, Instant.now());
      if (!exited) {
        return new ProcessResult(
            TIMEOUT_EXIT_CODE,
            capture.toString(),
            "Process timed out after " + request.timeout(),
            elapsed);
      }
      return new ProcessResult(process.exitValue(), capture.toString(), "", elapsed);
    } catch (InterruptedException e) {
      terminate(process);
      joinPump(pump);
      Thread.currentThread().interrupt();
      throw new ShellExecutionException("Process interrupted: " + firstArgument(request), e);
    }
  }

  private static Process start(Request request) {
    var builder = new ProcessBuilder(request.command());
    builder.redirectErrorStream(true);
    builder.environment().putAll(request.environment());
    request.workingDirectory().ifPresent(dir -> builder.directory(dir.toFile()));
    try {
      return builder.start();
    } catch (IOException e) {
      throw new ShellExecutionException("Failed to start process: " + firstArgument(request), e);
    }
  }

  private static Thread startOutputPump(
      Process process, BoundedTextCapture capture, Consumer<String> sink) {
    return Thread.ofVirtual()
        .name("fluxion-process-output")
        .start(() -> pumpOutput(process, capture, sink));
  }

  private static void pumpOutput(
      Process process, BoundedTextCapture capture, Consumer<String> sink) {
    var line = new StringBuilder();
    char[] buffer = new char[8192];
    try (Reader reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
      int read;
      while ((read = reader.read(buffer)) != -1) {
        capture.append(buffer, 0, read);
        emitLines(buffer, read, line, sink);
      }
    } catch (IOException ignored) {
      // The stream closes when the process is destroyed; whatever was captured still stands.
    }
    if (!line.isEmpty()) {
      accept(sink, line.toString());
    }
  }

  private static void emitLines(
      char[] buffer, int length, StringBuilder line, Consumer<String> sink) {
    for (int i = 0; i < length; i++) {
      char value = buffer[i];
      if (value == '\n') {
        accept(sink, stripCarriageReturn(line));
        line.setLength(0);
      } else {
        line.append(value);
      }
    }
  }

  private static String stripCarriageReturn(StringBuilder line) {
    int end = line.length();
    while (end > 0 && line.charAt(end - 1) == '\r') {
      end--;
    }
    return line.substring(0, end);
  }

  private static void accept(Consumer<String> sink, String line) {
    try {
      sink.accept(line);
    } catch (RuntimeException ignored) {
      // A failing renderer must never take down the process being rendered.
    }
  }

  private static void writeStdin(Process process, Optional<byte[]> stdin) {
    try (OutputStream out = process.getOutputStream()) {
      if (stdin.isPresent()) {
        byte[] bytes = stdin.orElseThrow();
        try {
          out.write(bytes);
          out.flush();
        } finally {
          Arrays.fill(bytes, (byte) 0);
        }
      }
    } catch (IOException ignored) {
      // A child that exits before reading stdin closes the pipe; that is not our failure.
    }
  }

  private static boolean awaitExit(Process process, Duration timeout) throws InterruptedException {
    try {
      process.onExit().get(Math.max(timeout.toNanos(), 1L), TimeUnit.NANOSECONDS);
      return true;
    } catch (TimeoutException e) {
      terminate(process);
      return false;
    } catch (ExecutionException e) {
      return !process.isAlive();
    }
  }

  private static void terminate(Process process) {
    List<ProcessHandle> descendants = process.descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    process.destroy();
    if (waitForDeath(process)) {
      return;
    }
    descendants.forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    waitForDeath(process);
  }

  private static boolean waitForDeath(Process process) {
    try {
      return process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void joinPump(Thread pump) {
    try {
      pump.join(DRAIN_GRACE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String firstArgument(Request request) {
    return request.command().isEmpty() ? "<empty command>" : request.command().getFirst();
  }
}
