package dev.sysboot.cli.command;

import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "import",
    description = "Generate review-required profile fragments from the host",
    subcommands = {ImportCommand.PackagesSubcommand.class})
public final class ImportCommand implements Runnable {

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().getOut().println("Run 'fluxion import --help' for subcommands.");
  }

  @Command(name = "packages", description = "Import installed host packages into a YAML fragment")
  public static final class PackagesSubcommand implements Runnable {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_PACKAGES = 20_000;

    @Spec private CommandSpec spec;

    @Option(
        names = {"--from-host"},
        description = "Read package names from the current host",
        required = true)
    private boolean fromHost;

    @Option(
        names = {"--output"},
        description = "Output YAML path",
        required = true)
    private Path output;

    @Option(
        names = {"--force"},
        description = "Overwrite the output file if it exists")
    private boolean force;

    @Override
    public void run() {
      if (!fromHost) {
        throw new CliFailureException(ExitCode.INVALID_INPUT, "Specify --from-host");
      }
      if (Files.exists(output) && !force) {
        throw new CliFailureException(
            ExitCode.INVALID_INPUT, "Output file already exists. Use --force to overwrite.");
      }
      HostPackages packages = detectPackages();
      writeFragment(packages);
      spec.commandLine().getOut().println("Imported packages: " + output.toAbsolutePath());
    }

    private HostPackages detectPackages() {
      if (commandExists("rpm")) {
        CommandResult result = commandLines("rpm", "-qa", "--qf", "%{NAME}\n");
        if (result.success()) {
          return new HostPackages(rpmManager(), result.lines());
        }
      }
      if (commandExists("pacman")) {
        CommandResult result = commandLines("pacman", "-Qq");
        if (result.success()) {
          return new HostPackages("pacman", result.lines());
        }
      }
      if (commandExists("dpkg-query")) {
        CommandResult result = commandLines("dpkg-query", "-W", "-f=${binary:Package}\n");
        if (result.success()) {
          return new HostPackages("apt", result.lines());
        }
      }
      throw new CliFailureException(
          ExitCode.EXTERNAL_DEPENDENCY_ERROR, "No supported host package database found");
    }

    private String rpmManager() {
      if (commandExists("dnf")) {
        return "dnf";
      }
      if (commandExists("zypper")) {
        return "zypper";
      }
      return "dnf";
    }

    private void writeFragment(HostPackages packages) {
      try {
        if (output.getParent() != null) {
          Files.createDirectories(output.getParent());
        }
        Files.writeString(output, render(packages));
      } catch (IOException e) {
        throw new CliFailureException(
            ExitCode.IO_ERROR, "Failed to write import fragment: " + output.toAbsolutePath(), e);
      }
    }

    private String render(HostPackages packages) {
      return """
      # Review required. Generated from this host's package database.
      # Remove machine-specific, transient, or unwanted packages before applying.
      jobs:
        - name: imported-packages
          restartPolicy:
            type: none
          steps:
            - type: packages
              name: imported-packages
              packageManager: %s
              packages:
      %s
      """
          .formatted(packages.packageManager(), packageLines(packages.names()));
    }

    private String packageLines(List<String> packages) {
      if (packages.isEmpty()) {
        return "          []\n";
      }
      var output = new StringBuilder();
      packages.forEach(
          name ->
              output.append("          - ").append(quoteYaml(name)).append(System.lineSeparator()));
      return output.toString();
    }

    private String quoteYaml(String value) {
      return value.matches("[A-Za-z0-9_.+:-]+") ? value : "\"" + value.replace("\"", "\\\"") + "\"";
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
      Process process;
      try {
        process =
            new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
      } catch (IOException e) {
        return new CommandResult(false, List.of());
      }
      return collect(process);
    }

    private CommandResult collect(Process process) {
      CompletableFuture<List<String>> output =
          CompletableFuture.supplyAsync(
              () -> process.inputReader().lines().limit(MAX_PACKAGES).toList());
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
      return lines.stream().map(String::strip).filter(line -> !line.isBlank()).sorted().toList();
    }

    private record HostPackages(String packageManager, List<String> names) {
      private HostPackages {
        names = List.copyOf(names);
      }
    }

    private record CommandResult(boolean success, List<String> lines) {
      private CommandResult {
        lines = List.copyOf(lines);
      }
    }
  }
}
