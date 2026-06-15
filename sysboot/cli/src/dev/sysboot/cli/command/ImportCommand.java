package dev.sysboot.cli.command;

import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "import",
    description = "Generate review-required profile fragments from the host",
    subcommands = {ImportCommand.PackagesSubcommand.class, ImportCommand.FlatpaksSubcommand.class})
public final class ImportCommand implements Runnable {

  private static final HostCommandRunner HOST_COMMANDS =
      new HostCommandRunner(Duration.ofSeconds(5), 20_000);

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    spec.commandLine().getOut().println("Run 'fluxion import --help' for subcommands.");
  }

  @Command(name = "packages", description = "Import installed host packages into a YAML fragment")
  public static final class PackagesSubcommand implements Runnable {

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
      if (HOST_COMMANDS.commandExists("rpm")) {
        HostCommandRunner.CommandResult result =
            HOST_COMMANDS.lines("rpm", "-qa", "--qf", "%{NAME}\n");
        if (result.success()) {
          return new HostPackages(rpmManager(), result.lines());
        }
      }
      if (HOST_COMMANDS.commandExists("pacman")) {
        HostCommandRunner.CommandResult result = HOST_COMMANDS.lines("pacman", "-Qq");
        if (result.success()) {
          return new HostPackages("pacman", result.lines());
        }
      }
      if (HOST_COMMANDS.commandExists("dpkg-query")) {
        HostCommandRunner.CommandResult result =
            HOST_COMMANDS.lines("dpkg-query", "-W", "-f=${binary:Package}\n");
        if (result.success()) {
          return new HostPackages("apt", result.lines());
        }
      }
      throw new CliFailureException(
          ExitCode.EXTERNAL_DEPENDENCY_ERROR, "No supported host package database found");
    }

    private String rpmManager() {
      if (HOST_COMMANDS.commandExists("dnf")) {
        return "dnf";
      }
      if (HOST_COMMANDS.commandExists("zypper")) {
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

    private record HostPackages(String packageManager, List<String> names) {
      private HostPackages {
        names = List.copyOf(names);
      }
    }
  }

  @Command(name = "flatpaks", description = "Import installed Flatpak apps into a YAML fragment")
  public static final class FlatpaksSubcommand implements Runnable {

    @Spec private CommandSpec spec;

    @Option(
        names = {"--from-host"},
        description = "Read Flatpak apps from the current host",
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
      HostFlatpaks flatpaks = detectFlatpaks();
      writeFragment(flatpaks);
      spec.commandLine().getOut().println("Imported Flatpaks: " + output.toAbsolutePath());
    }

    private HostFlatpaks detectFlatpaks() {
      if (!HOST_COMMANDS.commandExists("flatpak")) {
        throw new CliFailureException(
            ExitCode.EXTERNAL_DEPENDENCY_ERROR, "Flatpak command not found");
      }
      HostCommandRunner.CommandResult apps =
          HOST_COMMANDS.lines("flatpak", "list", "--app", "--columns=application");
      if (!apps.success() || apps.lines().isEmpty()) {
        throw new CliFailureException(
            ExitCode.EXTERNAL_DEPENDENCY_ERROR, "No installed Flatpak apps found");
      }
      HostCommandRunner.CommandResult remotes =
          HOST_COMMANDS.lines("flatpak", "remotes", "--columns=name");
      return new HostFlatpaks(preferredRemote(remotes.lines()), apps.lines());
    }

    private String preferredRemote(List<String> remotes) {
      if (remotes.contains("flathub")) {
        return "flathub";
      }
      return remotes.isEmpty() ? "flathub" : remotes.getFirst();
    }

    private void writeFragment(HostFlatpaks flatpaks) {
      try {
        if (output.getParent() != null) {
          Files.createDirectories(output.getParent());
        }
        Files.writeString(output, render(flatpaks));
      } catch (IOException e) {
        throw new CliFailureException(
            ExitCode.IO_ERROR, "Failed to write import fragment: " + output.toAbsolutePath(), e);
      }
    }

    private String render(HostFlatpaks flatpaks) {
      return """
      # Review required. Generated from this host's Flatpak installation.
      # Remove machine-specific, transient, or unwanted apps before applying.
      jobs:
        - name: imported-flatpaks
          restartPolicy:
            type: none
          steps:
            - type: flatpak
              name: imported-flatpaks
              remote: %s
              appIds:
      %s
      """
          .formatted(flatpaks.remote(), appLines(flatpaks.appIds()));
    }

    private String appLines(List<String> appIds) {
      var output = new StringBuilder();
      appIds.forEach(
          appId -> output.append("          - ").append(appId).append(System.lineSeparator()));
      return output.toString();
    }

    private record HostFlatpaks(String remote, List<String> appIds) {
      private HostFlatpaks {
        appIds = List.copyOf(appIds);
      }
    }
  }
}
