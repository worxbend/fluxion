package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.PhaseExecutionPlanner;
import dev.sysboot.executor.StatusReport;
import dev.sysboot.executor.StatusReportBuilder;
import java.util.LinkedHashMap;
import java.util.List;
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

  @Option(names = "--summary", description = "Print only aggregate status counts")
  private boolean summary;

  @Option(names = "--missing", description = "Show configured items that are missing")
  private boolean missingOnly;

  @Option(names = "--state-only", description = "Show state entries absent from the config")
  private boolean stateOnly;

  @Option(names = "--failed", description = "Show missing, unknown, and version-drift items")
  private boolean failedOnly;

  @Option(names = "--version-drift", description = "Show only items whose live version differs")
  private boolean versionDriftOnly;

  @Override
  public void run() {
    var context = ApplicationContext.create(true, profile, false, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());
    Optional<BootstrapState> state = new JsonStateRepository(new ObjectMapper()).load(profile);

    if (resumeCommand) {
      writeResumeCommand(config, state);
      return;
    }

    var plan = context.executionPlanBuilder().build(config);
    var liveResults = context.parallelProbeRunner().probeAll(config.modules(), ignored -> {});
    StatusReport report = new StatusReportBuilder().build(plan, state, liveResults);
    List<StatusReport.Item> items = filteredItems(report);

    if (format == OutputFormat.JSON) {
      JsonOutput.write(spec.commandLine().getOut(), jsonStatus(report, items));
      return;
    }

    if (summary) {
      writeSummary(report);
      return;
    }

    var out = spec.commandLine().getOut();
    out.printf("%-45s  %-15s  %-20s  %s%n", "Item", "Type", "Status", "Detail");
    out.println("-".repeat(80));

    if (items.isEmpty()) {
      out.println("(no items found)");
      return;
    }

    items.forEach(
        item ->
            out.printf(
                "%-45s  %-15s  %-20s  %s%n",
                truncate(item.key(), 45),
                item.type(),
                statusKind(item.classification()),
                item.detail()));
  }

  private List<StatusReport.Item> filteredItems(StatusReport report) {
    return report.items().stream().filter(this::includeItem).toList();
  }

  private boolean includeItem(StatusReport.Item item) {
    if (missingOnly) {
      return item.classification() == StatusReport.Classification.CONFIGURED_MISSING;
    }
    if (stateOnly) {
      return item.classification() == StatusReport.Classification.STATE_ONLY;
    }
    if (failedOnly) {
      return switch (item.classification()) {
        case CONFIGURED_MISSING, UNKNOWN, VERSION_DRIFT -> true;
        case CONFIGURED_INSTALLED, STATE_ONLY -> false;
      };
    }
    if (versionDriftOnly) {
      return item.classification() == StatusReport.Classification.VERSION_DRIFT;
    }
    return true;
  }

  private Map<String, Object> jsonStatus(StatusReport report, List<StatusReport.Item> items) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", report.profileName());
    output.put("summary", jsonSummary(report.summary()));
    output.put("items", summary ? List.of() : items.stream().map(this::jsonStatusItem).toList());
    return output;
  }

  private Map<String, Object> jsonStatusItem(StatusReport.Item item) {
    var output = new LinkedHashMap<String, Object>();
    output.put("key", item.key());
    output.put("displayName", item.displayName());
    output.put("type", item.type());
    output.put("status", statusKind(item.classification()));
    output.put("detail", item.detail());
    output.put("stateVersion", item.stateVersion());
    output.put("liveVersion", item.liveVersion());
    return output;
  }

  private Map<String, Object> jsonSummary(StatusReport.Summary summary) {
    var output = new LinkedHashMap<String, Object>();
    output.put("total", summary.total());
    output.put("configuredInstalled", summary.configuredInstalled());
    output.put("configuredMissing", summary.configuredMissing());
    output.put("stateOnly", summary.stateOnly());
    output.put("unknown", summary.unknown());
    output.put("versionDrift", summary.versionDrift());
    return output;
  }

  private String statusKind(StatusReport.Classification classification) {
    return switch (classification) {
      case CONFIGURED_INSTALLED -> "configured-installed";
      case CONFIGURED_MISSING -> "configured-missing";
      case STATE_ONLY -> "state-only";
      case UNKNOWN -> "unknown";
      case VERSION_DRIFT -> "version-drift";
    };
  }

  private void writeSummary(StatusReport report) {
    var out = spec.commandLine().getOut();
    StatusReport.Summary counts = report.summary();
    out.printf("Profile: %s%n", report.profileName());
    out.printf("Total: %d%n", counts.total());
    out.printf("Configured installed: %d%n", counts.configuredInstalled());
    out.printf("Configured missing: %d%n", counts.configuredMissing());
    out.printf("State-only: %d%n", counts.stateOnly());
    out.printf("Unknown: %d%n", counts.unknown());
    out.printf("Version drift: %d%n", counts.versionDrift());
  }

  private String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 3) + "...";
  }

  private void writeResumeCommand(BootstrapConfig config, Optional<BootstrapState> state) {
    Optional<String> nextPhase = nextIncompletePhase(config, state);
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

  private Optional<String> nextIncompletePhase(
      BootstrapConfig config, Optional<BootstrapState> state) {
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
