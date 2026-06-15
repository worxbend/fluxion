package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.PhaseExecutionPlanner;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "status", description = "Show installation status for all items in a profile")
public final class StatusCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Spec private CommandSpec spec;

  @Option(
      names = {"--profile"},
      description = "Profile name",
      paramLabel = "PROFILE",
      defaultValue = "default")
  private String profile;

  @Option(
      names = {"--resume-command"},
      description = "Print the command that resumes the next incomplete phase")
  private boolean resumeCommand;

  @Override
  public void run() {
    var context = ApplicationContext.create(true, profile, false, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());

    if (resumeCommand) {
      printResumeCommand(config);
      return;
    }

    var out = spec.commandLine().getOut();
    out.printf("%-45s  %-15s  %s%n", "Item", "Type", "Status");
    out.println("-".repeat(80));

    Map<String, InstallationStatus> results =
        context.parallelProbeRunner().probeAll(config.modules(), ignored -> {});

    if (results.isEmpty()) {
      out.println("(no items found)");
      return;
    }

    results.forEach(
        (key, status) -> {
          String statusLabel =
              switch (status) {
                case InstallationStatus.InstalledByProbe p ->
                    "installed"
                        + (p.detectedVersion() != null ? " (" + p.detectedVersion() + ")" : "");
                case InstallationStatus.InstalledFromState s ->
                    "from-state (" + s.installedAt() + ")";
                case InstallationStatus.NotInstalled ignored -> "not installed";
                case InstallationStatus.Unknown u -> "unknown: " + u.reason();
              };
          out.printf("%-45s  %-15s  %s%n", truncate(key, 45), "", statusLabel);
        });
  }

  private String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 3) + "...";
  }

  private void printResumeCommand(BootstrapConfig config) {
    Optional<String> nextPhase = nextIncompletePhase(config);
    if (nextPhase.isEmpty()) {
      spec.commandLine().getOut().println("Profile is complete; no resume command.");
      return;
    }
    spec.commandLine()
        .getOut()
        .println(
            ResumeCommandFormatter.command(options.resolvedConfigFile(), profile, nextPhase));
  }

  private Optional<String> nextIncompletePhase(BootstrapConfig config) {
    var repo = new JsonStateRepository(new ObjectMapper());
    Optional<BootstrapState> state = repo.load(profile);
    return new PhaseExecutionPlanner().plan(config.phases()).stream()
        .filter(
            phase ->
                state
                    .map(saved -> !saved.isPhaseCompleted(phase.name().value()))
                    .orElse(true))
        .map(phase -> phase.name().value())
        .findFirst();
  }
}
