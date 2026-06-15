package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.executor.ExecutionPlan;
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

@Command(name = "explain", description = "Explain why a phase or item would run or skip")
public final class ExplainCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Spec private CommandSpec spec;

  @Option(
      names = {"--profile"},
      description = "Profile name",
      paramLabel = "PROFILE",
      defaultValue = "default")
  private String profile;

  @Option(names = "--phase", description = "Phase name to explain")
  private String phaseName;

  @Option(names = "--item", description = "Item key or display name to explain")
  private String itemKey;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private OutputFormat format;

  @Override
  public void run() {
    validateSelector();
    var context = ApplicationContext.create(true, profile, false, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());
    Optional<BootstrapState> state = new JsonStateRepository(new ObjectMapper()).load(profile);
    ExecutionPlan plan = context.executionPlanBuilder().build(config);
    var liveResults = context.parallelProbeRunner().probeAll(config.modules(), ignored -> {});
    StatusReport report = new StatusReportBuilder().build(plan, state, liveResults);
    Explanation explanation = explanation(plan, report);

    if (format == OutputFormat.JSON) {
      JsonOutput.write(spec.commandLine().getOut(), jsonExplanation(explanation));
      return;
    }
    writeText(explanation);
  }

  private void validateSelector() {
    boolean hasPhase = phaseName != null && !phaseName.isBlank();
    boolean hasItem = itemKey != null && !itemKey.isBlank();
    if (hasPhase == hasItem) {
      throw new CliFailureException(
          ExitCode.INVALID_INPUT, "Specify exactly one of --phase or --item");
    }
  }

  private Explanation explanation(ExecutionPlan plan, StatusReport report) {
    if (phaseName != null && !phaseName.isBlank()) {
      ExecutionPlan.Phase phase =
          plan.phases().stream()
              .filter(candidate -> candidate.name().equals(phaseName))
              .findFirst()
              .orElseThrow(() -> notFound("phase", phaseName));
      return phaseExplanation(phase, report);
    }
    return itemExplanation(plan, report, itemKey);
  }

  private Explanation phaseExplanation(ExecutionPlan.Phase phase, StatusReport report) {
    List<ExplainedItem> items =
        phase.modules().stream()
            .flatMap(
                module -> module.items().stream().map(item -> explainedItem(module, item, report)))
            .toList();
    return new Explanation(
        "phase",
        phase.name(),
        phase.name(),
        phase.name(),
        null,
        phase.dependsOn(),
        phase.restartEffect().name().toLowerCase(),
        null,
        "phase contains " + items.size() + " item(s)",
        List.of(),
        items);
  }

  private Explanation itemExplanation(ExecutionPlan plan, StatusReport report, String itemKey) {
    for (ExecutionPlan.Phase phase : plan.phases()) {
      for (ExecutionPlan.Module module : phase.modules()) {
        Optional<ExecutionPlan.Item> item = findItem(module.items(), itemKey);
        if (item.isPresent()) {
          ExplainedItem explained = explainedItem(module, item.orElseThrow(), report);
          return new Explanation(
              "item",
              explained.key(),
              explained.displayName(),
              phase.name(),
              module.name(),
              phase.dependsOn(),
              phase.restartEffect().name().toLowerCase(),
              explained.status(),
              explained.detail(),
              explained.commandPreview(),
              List.of(explained));
        }
      }
    }
    throw notFound("item", itemKey);
  }

  private Optional<ExecutionPlan.Item> findItem(List<ExecutionPlan.Item> items, String key) {
    return items.stream()
        .filter(
            item ->
                item.item().key().equals(key)
                    || item.item().displayName().equals(key)
                    || item.item().moduleName().value().equals(key))
        .findFirst();
  }

  private ExplainedItem explainedItem(
      ExecutionPlan.Module module, ExecutionPlan.Item item, StatusReport report) {
    StatusReport.Item status = statusFor(report, item);
    return new ExplainedItem(
        item.item().key(),
        item.item().displayName(),
        module.name(),
        item.item().itemType().name().toLowerCase(),
        statusKind(status.classification()),
        status.detail(),
        item.commandPreview().orElse(List.of()));
  }

  private StatusReport.Item statusFor(StatusReport report, ExecutionPlan.Item item) {
    return report.items().stream()
        .filter(candidate -> candidate.key().equals(item.item().key()))
        .findFirst()
        .orElseThrow(() -> notFound("status for item", item.item().key()));
  }

  private CliFailureException notFound(String kind, String value) {
    return new CliFailureException(ExitCode.INVALID_INPUT, "Unknown " + kind + ": " + value);
  }

  private void writeText(Explanation explanation) {
    var out = spec.commandLine().getOut();
    out.printf("Explain %s: %s%n", explanation.kind(), explanation.displayName());
    out.printf("Phase: %s%n", explanation.phaseName());
    if (explanation.moduleName() != null) {
      out.printf("Module: %s%n", explanation.moduleName());
    }
    out.printf(
        "Depends on: %s%n", explanation.dependsOn().isEmpty() ? "(none)" : explanation.dependsOn());
    out.printf("Restart effect: %s%n", explanation.restartEffect());
    if (explanation.status() != null) {
      out.printf("Status: %s%n", explanation.status());
    }
    out.printf("Reason: %s%n", explanation.detail());
    if (!explanation.commandPreview().isEmpty()) {
      out.println("Command preview: " + String.join(" ", explanation.commandPreview()));
    }
  }

  private Map<String, Object> jsonExplanation(Explanation explanation) {
    var output = new LinkedHashMap<String, Object>();
    output.put("kind", explanation.kind());
    output.put("key", explanation.key());
    output.put("displayName", explanation.displayName());
    output.put("phaseName", explanation.phaseName());
    output.put("moduleName", explanation.moduleName());
    output.put("dependsOn", explanation.dependsOn());
    output.put("restartEffect", explanation.restartEffect());
    output.put("status", explanation.status());
    output.put("detail", explanation.detail());
    output.put("commandPreview", explanation.commandPreview());
    output.put("items", explanation.items().stream().map(this::jsonItem).toList());
    return output;
  }

  private Map<String, Object> jsonItem(ExplainedItem item) {
    var output = new LinkedHashMap<String, Object>();
    output.put("key", item.key());
    output.put("displayName", item.displayName());
    output.put("moduleName", item.moduleName());
    output.put("type", item.type());
    output.put("status", item.status());
    output.put("detail", item.detail());
    output.put("commandPreview", item.commandPreview());
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

  private record Explanation(
      String kind,
      String key,
      String displayName,
      String phaseName,
      String moduleName,
      List<String> dependsOn,
      String restartEffect,
      String status,
      String detail,
      List<String> commandPreview,
      List<ExplainedItem> items) {}

  private record ExplainedItem(
      String key,
      String displayName,
      String moduleName,
      String type,
      String status,
      String detail,
      List<String> commandPreview) {}
}
