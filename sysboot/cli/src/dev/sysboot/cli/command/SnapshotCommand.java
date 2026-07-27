package dev.sysboot.cli.command;

import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.output.JsonOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "snapshot", description = "Write a review-required host inventory snapshot")
public final class SnapshotCommand implements Runnable {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
  private static final int MAX_LINES = 20_000;

  @Spec private CommandSpec spec;

  @Option(
      names = {"--output"},
      description = "Output JSON path",
      required = true)
  private Path output;

  @Option(
      names = {"--force"},
      description = "Overwrite the output file if it exists")
  private boolean force;

  @Override
  public void run() {
    if (Files.exists(output) && !force) {
      throw new CliFailureException(
          ExitCode.INVALID_INPUT, "Output file already exists. Use --force to overwrite.");
    }
    try {
      writeSnapshot(snapshot());
      spec.commandLine().getOut().println("Snapshot written: " + output.toAbsolutePath());
    } catch (IOException e) {
      throw new CliFailureException(
          ExitCode.IO_ERROR, "Failed to write snapshot: " + output.toAbsolutePath(), e);
    }
  }

  private Map<String, Object> snapshot() {
    var snapshot = new LinkedHashMap<String, Object>();
    snapshot.put("schemaVersion", 1);
    snapshot.put("generatedAt", Instant.now().toString());
    snapshot.put("reviewRequired", true);
    snapshot.put("warning", "Generated host inventory. Review before turning it into a profile.");
    snapshot.put("osRelease", osRelease());
    snapshot.put("defaultShell", System.getenv().getOrDefault("SHELL", ""));
    snapshot.put("packageManagers", packageManagers());
    snapshot.put("packages", packages());
    snapshot.put("flatpak", flatpak());
    snapshot.put("toolchains", toolchains());
    return snapshot;
  }

  private void writeSnapshot(Map<String, Object> snapshot) throws IOException {
    if (output.getParent() != null) {
      Files.createDirectories(output.getParent());
    }
    Files.writeString(output, JsonOutput.toJson(snapshot) + System.lineSeparator());
  }

  private Map<String, String> osRelease() {
    Path osRelease = Path.of("/etc/os-release");
    if (!Files.isReadable(osRelease)) {
      return Map.of();
    }
    try {
      var values = new LinkedHashMap<String, String>();
      Files.readString(osRelease)
          .lines()
          .filter(line -> line.contains("="))
          .forEach(line -> putOsReleaseValue(values, line));
      return values;
    } catch (IOException e) {
      return Map.of();
    }
  }

  private void putOsReleaseValue(Map<String, String> values, String line) {
    int separator = line.indexOf('=');
    String key = line.substring(0, separator);
    String value = line.substring(separator + 1).replace("\"", "");
    if (List.of("ID", "ID_LIKE", "VERSION_ID", "PRETTY_NAME").contains(key)) {
      values.put(key.toLowerCase(Locale.ROOT), value);
    }
  }

  private Map<String, Boolean> packageManagers() {
    var managers = new LinkedHashMap<String, Boolean>();
    List.of("dnf", "pacman", "paru", "yay", "apt-get", "zypper", "flatpak", "cargo")
        .forEach(command -> managers.put(command, commandExists(command)));
    return managers;
  }

  private Map<String, Object> packages() {
    var packages = new LinkedHashMap<String, Object>();
    putIfPresent(packages, "rpm", commandLines("rpm", "-qa", "--qf", "%{NAME}\n"));
    putIfPresent(packages, "pacman", commandLines("pacman", "-Qq"));
    putIfPresent(packages, "apt", commandLines("dpkg-query", "-W", "-f=${binary:Package}\n"));
    return packages;
  }

  private Map<String, Object> flatpak() {
    var flatpak = new LinkedHashMap<String, Object>();
    putIfPresent(
        flatpak, "apps", commandLines("flatpak", "list", "--app", "--columns=application"));
    putIfPresent(flatpak, "remotes", commandLines("flatpak", "remotes", "--columns=name,url"));
    return flatpak;
  }

  private Map<String, Boolean> toolchains() {
    var toolchains = new LinkedHashMap<String, Boolean>();
    toolchains.put("rustup", commandExists("rustup"));
    toolchains.put(
        "sdkman", Files.isDirectory(Path.of(System.getProperty("user.home"), ".sdkman")));
    toolchains.put("juliaup", commandExists("juliaup"));
    return toolchains;
  }

  private void putIfPresent(Map<String, Object> output, String key, CommandResult result) {
    if (result.success()) {
      output.put(key, result.lines());
    }
  }

  private boolean commandExists(String command) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return false;
    }
    return java.util.Arrays.stream(path.split(java.io.File.pathSeparator))
        .map(Path::of)
        .map(dir -> dir.resolve(command))
        .anyMatch(Files::isExecutable);
  }

  private CommandResult commandLines(String... command) {
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
        CompletableFuture.supplyAsync(
            () -> process.inputReader().lines().limit(MAX_LINES).toList());
    try {
      if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
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
    return lines.stream().filter(line -> !line.isBlank()).sorted().toList();
  }

  private record CommandResult(boolean success, List<String> lines) {
    private CommandResult {
      lines = List.copyOf(lines);
    }
  }
}
