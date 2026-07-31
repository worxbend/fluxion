package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class DetachedProcessTree {

  private static final Optional<String> SETSID = fixedExecutable("/usr/bin/setsid", "/bin/setsid");
  private static final Optional<String> KILL = fixedExecutable("/usr/bin/kill", "/bin/kill");

  private DetachedProcessTree() {}

  public static List<String> command(List<String> command) {
    if (SETSID.isEmpty() || command.isEmpty()) {
      return List.copyOf(command);
    }
    var detached = new ArrayList<String>(command.size() + 2);
    detached.add(SETSID.orElseThrow());
    detached.add("-w");
    detached.addAll(command);
    return List.copyOf(detached);
  }

  public static void terminate(Process process, Duration grace) {
    List<ProcessHandle> descendants = process.descendants().toList();
    signalGroup(process, "TERM");
    descendants.forEach(ProcessHandle::destroy);
    process.destroy();
    waitFor(process, grace);
    signalGroup(process, "KILL");
    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
    waitFor(process, grace);
  }

  private static void signalGroup(Process process, String signal) {
    if (SETSID.isEmpty() || KILL.isEmpty()) {
      return;
    }
    try {
      new ProcessBuilder(KILL.orElseThrow(), "-" + signal, "--", "-" + process.pid())
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor(1, TimeUnit.SECONDS);
    } catch (IOException ignored) {
      // ProcessHandle termination remains available.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void waitFor(Process process, Duration grace) {
    try {
      process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static Optional<String> fixedExecutable(String... candidates) {
    return java.util.Arrays.stream(candidates)
        .filter(path -> Files.isExecutable(Path.of(path)))
        .findFirst();
  }
}
