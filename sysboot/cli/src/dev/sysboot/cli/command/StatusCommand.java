package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.PhaseExecutionPlanner;
import java.util.LinkedHashMap;
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

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private OutputFormat format;

  @Override
  public void run() {
    var context = ApplicationContext.create(true, profile, false, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());

    if (resumeCommand) {
      writeResumeCommand(config);
      return;
    }

    Map<String, InstallationStatus> results =
        context.parallelProbeRunner().probeAll(config.modules(), ignored -> {});

    if (format == OutputFormat.JSON) {
      JsonOutput.write(spec.commandLine().getOut(), jsonStatus(config, results));
      return;
    }

    var out = spec.commandLine().getOut();
    out.printf("%-45s  %-15s  %s%n", "Item", "Type", "Status");
    out.println("-".repeat(80));

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

  private Map<String, Object> jsonStatus(
      BootstrapConfig config, Map<String, InstallationStatus> results) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", config.profileName().value());
    output.put("items", results.entrySet().stream().map(this::jsonStatusItem).toList());
    return output;
  }

  private Map<String, Object> jsonStatusItem(Map.Entry<String, InstallationStatus> entry) {
    var output = new LinkedHashMap<String, Object>();
    output.put("key", entry.getKey());
    output.put("status", statusKind(entry.getValue()));
    output.put("detail", statusDetail(entry.getValue()));
    return output;
  }

  private String statusKind(InstallationStatus status) {
    return switch (status) {
      case InstallationStatus.InstalledByProbe ignored -> "installed";
      case InstallationStatus.InstalledFromState ignored -> "from-state";
      case InstallationStatus.NotInstalled ignored -> "not-installed";
      case InstallationStatus.Unknown ignored -> "unknown";
    };
  }

  private String statusDetail(InstallationStatus status) {
    return switch (status) {
      case InstallationStatus.InstalledByProbe p -> p.detectedVersion();
      case InstallationStatus.InstalledFromState s -> s.installedAt().toString();
      case InstallationStatus.NotInstalled ignored -> null;
      case InstallationStatus.Unknown u -> u.reason();
    };
  }

  private String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 3) + "...";
  }

  private void writeResumeCommand(BootstrapConfig config) {
    Optional<String> nextPhase = nextIncompletePhase(config);
    if (format == OutputFormat.JSON) {
      JsonOutput.write(spec.commandLine().getOut(), jsonResumeCommand(config, nextPhase));
      return;
    }
    if (nextPhase.isEmpty()) {
      spec.commandLine().getOut().println("Profile is complete; no resume command.");
      return;
    }
    spec.commandLine()
        .getOut()
        .println(ResumeCommandFormatter.command(options.resolvedConfigFile(), profile, nextPhase));
  }

  private Optional<String> nextIncompletePhase(BootstrapConfig config) {
    var repo = new JsonStateRepository(new ObjectMapper());
    Optional<BootstrapState> state = repo.load(profile);
    return new PhaseExecutionPlanner()
        .plan(config.phases()).stream()
            .filter(
                phase ->
                    state.map(saved -> !saved.isPhaseCompleted(phase.name().value())).orElse(true))
            .map(phase -> phase.name().value())
            .findFirst();
  }

  private Map<String, Object> jsonResumeCommand(
      BootstrapConfig config, Optional<String> nextPhase) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", config.profileName().value());
    output.put("nextPhase", nextPhase.orElse(null));
    output.put(
        "command",
        nextPhase
            .map(
                ignored ->
                    ResumeCommandFormatter.command(
                        options.resolvedConfigFile(), profile, nextPhase))
            .orElse(null));
    return output;
  }
}
