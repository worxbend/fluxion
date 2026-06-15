package dev.sysboot.cli.command;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class HostCommandRunner {

  private final Duration timeout;
  private final int maxLines;

  HostCommandRunner(Duration timeout, int maxLines) {
    this.timeout = timeout;
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
      process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    } catch (IOException e) {
      return new CommandResult(false, List.of());
    }
    return collect(process);
  }

  private CommandResult collect(Process process) {
    CompletableFuture<List<String>> output =
        CompletableFuture.supplyAsync(() -> process.inputReader().lines().limit(maxLines).toList());
    try {
      if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return new CommandResult(false, List.of());
      }
      return new CommandResult(process.exitValue() == 0, sorted(output.join()));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return new CommandResult(false, List.of());
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
}
