package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.executor.JsonStateRepository;
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

@Command(name = "diff", description = "Show what would change on this host")
public final class DiffCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Spec private CommandSpec spec;

  @Option(
      names = {"--profile"},
      description = "Profile name",
      paramLabel = "PROFILE",
      defaultValue = "default")
  private String profile;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private OutputFormat format;

  @Override
  public void run() {
    var context = ApplicationContext.create(true, profile, false, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());
    Optional<BootstrapState> state = new JsonStateRepository(new ObjectMapper()).load(profile);
    var plan = context.executionPlanBuilder().build(config);
    var liveResults = context.parallelProbeRunner().probeAll(config.modules(), ignored -> {});
    StatusReport report = new StatusReportBuilder().build(plan, state, liveResults);
    List<StatusReport.Item> changes = changes(report);

    if (format == OutputFormat.JSON) {
      JsonOutput.write(spec.commandLine().getOut(), jsonDiff(report, changes));
      return;
    }
    writeText(report, changes);
  }

  private List<StatusReport.Item> changes(StatusReport report) {
    return report.items().stream()
        .filter(item -> item.classification() != StatusReport.Classification.CONFIGURED_INSTALLED)
        .toList();
  }

  private void writeText(StatusReport report, List<StatusReport.Item> changes) {
    var out = spec.commandLine().getOut();
    out.printf("Diff for profile: %s%n", report.profileName());
    out.println();
    if (changes.isEmpty()) {
      out.println("No changes detected.");
      return;
    }
    for (StatusReport.Classification classification : StatusReport.Classification.values()) {
      List<StatusReport.Item> group = group(changes, classification);
      if (!group.isEmpty()) {
        out.println(title(classification) + ":");
        group.forEach(
            item ->
                out.printf("  - %s (%s): %s%n", item.displayName(), item.type(), item.detail()));
        out.println();
      }
    }
  }

  private List<StatusReport.Item> group(
      List<StatusReport.Item> items, StatusReport.Classification classification) {
    return items.stream().filter(item -> item.classification() == classification).toList();
  }

  private Map<String, Object> jsonDiff(StatusReport report, List<StatusReport.Item> changes) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", report.profileName());
    output.put("summary", jsonSummary(report.summary()));
    output.put("changes", changes.stream().map(this::jsonChange).toList());
    return output;
  }

  private Map<String, Object> jsonChange(StatusReport.Item item) {
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

  private String title(StatusReport.Classification classification) {
    return switch (classification) {
      case CONFIGURED_INSTALLED -> "Already installed";
      case CONFIGURED_MISSING -> "Would install";
      case STATE_ONLY -> "Only in state";
      case UNKNOWN -> "Needs review";
      case VERSION_DRIFT -> "Version drift";
    };
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
}
