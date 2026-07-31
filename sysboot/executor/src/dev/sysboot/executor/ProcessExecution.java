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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single implementation of "run a child process safely".
 *
 * <p>Output is drained on a separate thread while the parent waits for exit. Draining and waiting
 * must overlap: reading to EOF first makes the timeout unreachable, and waiting first deadlocks as
 * soon as the child fills the pipe buffer. Timeouts terminate the whole process tree rather than
 * only the direct child, because package managers and installer scripts spawn helpers.
 */
final class ProcessExecution {

  private static final Logger log = LoggerFactory.getLogger(ProcessExecution.class);

  static final int TIMEOUT_EXIT_CODE = 124;

  /** A single line longer than this is truncated rather than split across redaction boundaries. */
  private static final int MAX_LINE_LENGTH = 64 * 1024;

  private static final String TRUNCATED_LINE = "[output line truncated]";

  private static final Duration TERMINATION_GRACE = Duration.ofSeconds(5);
  private static final Duration DRAIN_GRACE = Duration.ofSeconds(2);

  private ProcessExecution() {}

  record Request(
      List<String> command,
      Map<String, String> environment,
      Optional<Path> workingDirectory,
      Optional<byte[]> stdin,
      Duration timeout,
      Consumer<String> outputSink,
      boolean detachedSession) {

    Request {
      command = List.copyOf(command);
      environment = Map.copyOf(environment);
    }

    static Request of(List<String> command, Map<String, String> environment, Duration timeout) {
      return new Request(
          command, environment, Optional.empty(), Optional.empty(), timeout, line -> {}, true);
    }

    Request withStdin(byte[] bytes) {
      return new Request(
          command,
          environment,
          workingDirectory,
          Optional.of(bytes),
          timeout,
          outputSink,
          detachedSession);
    }

    Request withOutputSink(Consumer<String> sink) {
      return new Request(
          command, environment, workingDirectory, stdin, timeout, sink, detachedSession);
    }

    Request withWorkingDirectory(Optional<Path> directory) {
      return new Request(
          command, environment, directory, stdin, timeout, outputSink, detachedSession);
    }

    Request inSharedSession() {
      return new Request(command, environment, workingDirectory, stdin, timeout, outputSink, false);
    }
  }

  static ProcessResult run(Request request) {
    try {
      return runOwned(request);
    } finally {
      request.stdin().ifPresent(bytes -> Arrays.fill(bytes, (byte) 0));
    }
  }

  private static ProcessResult runOwned(Request request) {
    long timeoutNanos = validatedTimeoutNanos(request.timeout());
    Instant start = Instant.now();
    Process process = start(request);
    var capture = new BoundedTextCapture();
    Thread pump = startOutputPump(process, capture, request.outputSink());
    // Written on its own thread: a payload larger than the pipe buffer would otherwise block here,
    // before awaitExit has armed the timeout, and hang forever.
    Thread stdin = startStdinWriter(process, request.stdin());
    try {
      boolean exited = awaitExit(process, timeoutNanos);
      joinFor(stdin, DRAIN_GRACE);
      joinPump(pump, process);
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
      joinFor(stdin, DRAIN_GRACE);
      joinPump(pump, process);
      Thread.currentThread().interrupt();
      throw new ShellExecutionException("Process interrupted: " + firstArgument(request), e);
    }
  }

  private static Process start(Request request) {
    List<String> command =
        request.detachedSession()
            ? DetachedProcessTree.command(request.command())
            : request.command();
    var builder = new ProcessBuilder(command);
    builder.redirectErrorStream(true);
    builder.environment().putAll(request.environment());
    request.workingDirectory().ifPresent(dir -> configureWorkingDirectory(builder, dir));
    try {
      return builder.start();
    } catch (IOException e) {
      throw new ShellExecutionException("Failed to start process: " + firstArgument(request), e);
    }
  }

  private static void configureWorkingDirectory(ProcessBuilder builder, Path directory) {
    if (!java.nio.file.Files.isDirectory(directory)) {
      throw new ShellExecutionException("Working directory does not exist: " + directory);
    }
    builder.directory(directory.toFile());
  }

  private static Thread startOutputPump(
      Process process, BoundedTextCapture capture, Consumer<String> sink) {
    return Thread.ofVirtual()
        .name("fluxion-process-output")
        .start(() -> pumpOutput(process, capture, sink));
  }

  private static void pumpOutput(
      Process process, BoundedTextCapture capture, Consumer<String> sink) {
    var line = new StreamingLine();
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
    line.finish(sink);
  }

  private static void emitLines(
      char[] buffer, int length, StreamingLine line, Consumer<String> sink) {
    for (int i = 0; i < length; i++) {
      line.accept(buffer[i], sink);
    }
  }

  private static final class StreamingLine {
    private final StringBuilder content = new StringBuilder();
    private boolean discarding;

    void accept(char value, Consumer<String> sink) {
      if (value == '\n' || value == '\r') {
        finish(sink, value == '\n');
        return;
      }
      if (discarding) {
        return;
      }
      content.append(value);
      if (content.length() >= MAX_LINE_LENGTH) {
        content.setLength(0);
        discarding = true;
      }
    }

    void finish(Consumer<String> sink) {
      finish(sink, false);
    }

    private void finish(Consumer<String> sink, boolean emitEmpty) {
      if (discarding) {
        ProcessExecution.accept(sink, TRUNCATED_LINE);
      } else if (!content.isEmpty() || emitEmpty) {
        ProcessExecution.accept(sink, content.toString());
      }
      content.setLength(0);
      discarding = false;
    }
  }

  private static void accept(Consumer<String> sink, String line) {
    try {
      sink.accept(line);
    } catch (RuntimeException ignored) {
      // A failing renderer must never take down the process being rendered.
    }
  }

  private static Thread startStdinWriter(Process process, Optional<byte[]> stdin) {
    return Thread.ofVirtual().name("fluxion-process-stdin").start(() -> writeStdin(process, stdin));
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

  private static boolean awaitExit(Process process, long timeoutNanos) throws InterruptedException {
    try {
      process.onExit().get(timeoutNanos, TimeUnit.NANOSECONDS);
      return true;
    } catch (TimeoutException e) {
      terminate(process);
      return false;
    } catch (ExecutionException e) {
      if (process.isAlive()) {
        terminate(process);
        return false;
      }
      return true;
    }
  }

  private static void terminate(Process process) {
    DetachedProcessTree.terminate(process, TERMINATION_GRACE);
  }

  /**
   * Waits for the output pump, then guarantees it has stopped touching the capture.
   *
   * <p>The pump can outlive the child: a grandchild that inherited stdout keeps the pipe open, so
   * the read never returns EOF. Closing the stream unblocks it. Without that the pump could run
   * forever, and the caller would read the capture while it was still being written -- with no
   * happens-before edge, so the returned output could be torn.
   */
  private static void joinPump(Thread pump, Process process) {
    if (joinFor(pump, DRAIN_GRACE)) {
      return;
    }
    closeQuietly(process);
    if (!joinFor(pump, DRAIN_GRACE)) {
      log.debug("Output pump did not stop; captured output may be incomplete");
    }
  }

  private static boolean joinFor(Thread pump, Duration limit) {
    try {
      pump.join(limit);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return !pump.isAlive();
  }

  private static void closeQuietly(Process process) {
    try {
      process.getInputStream().close();
    } catch (IOException ignored) {
      // Already closed, or the pump is mid-read; either way there is nothing to recover.
    }
  }

  private static String firstArgument(Request request) {
    return request.command().isEmpty() ? "<empty command>" : request.command().getFirst();
  }

  private static long validatedTimeoutNanos(Duration timeout) {
    if (timeout == null || timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("Process timeout must be positive");
    }
    try {
      return timeout.toNanos();
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException("Process timeout is too large", e);
    }
  }
}
