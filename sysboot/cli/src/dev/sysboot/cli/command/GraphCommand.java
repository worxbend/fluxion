package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.executor.ExecutionPlan;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Renders the configured phase dependency graph without touching the host. */
@Command(name = "graph", description = "Render the phase dependency graph")
public final class GraphCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Spec private CommandSpec spec;

  @Option(
      names = "--format",
      defaultValue = "mermaid",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private GraphFormat format;

  @Override
  public void run() {
    var context = ApplicationContext.create(true);
    var config = context.configLoader().load(options.resolvedConfigFile());
    ExecutionPlan plan;
    try {
      plan = context.executionPlanBuilder().build(config);
    } catch (dev.sysboot.executor.CyclicDependencyException e) {
      throw new CliFailureException(
          ExitCode.CONFIGURATION_ERROR, "Cycle detected: " + e.getMessage(), e);
    }

    switch (format) {
      case JSON -> JsonOutput.write(spec.commandLine().getOut(), jsonGraph(plan));
      case DOT -> writeText(dotGraph(plan));
      case MERMAID -> writeText(mermaidGraph(plan));
    }
  }

  private void writeText(String output) {
    PrintWriter out = spec.commandLine().getOut();
    out.print(output);
    out.flush();
  }

  private Map<String, Object> jsonGraph(ExecutionPlan plan) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", plan.profileName());
    output.put("phases", plan.phases().stream().map(this::jsonPhase).toList());
    output.put(
        "edges", plan.phases().stream().flatMap(phase -> jsonEdges(phase).stream()).toList());
    return output;
  }

  private Map<String, Object> jsonPhase(ExecutionPlan.Phase phase) {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", phase.name());
    output.put("dependsOn", phase.dependsOn());
    output.put("moduleCount", phase.modules().size());
    output.put("restartEffect", phase.restartEffect().name().toLowerCase());
    return output;
  }

  private List<Map<String, String>> jsonEdges(ExecutionPlan.Phase phase) {
    return phase.dependsOn().stream().map(dep -> jsonEdge(dep, phase.name())).toList();
  }

  private Map<String, String> jsonEdge(String from, String to) {
    var output = new LinkedHashMap<String, String>();
    output.put("from", from);
    output.put("to", to);
    return output;
  }

  private String mermaidGraph(ExecutionPlan plan) {
    var output = new StringBuilder();
    output.append("flowchart TD").append(System.lineSeparator());
    plan.phases().forEach(phase -> output.append("  ").append(mermaidNode(phase.name())));
    plan.phases().forEach(phase -> appendMermaidEdges(output, phase));
    return output.toString();
  }

  private void appendMermaidEdges(StringBuilder output, ExecutionPlan.Phase phase) {
    phase
        .dependsOn()
        .forEach(
            dep ->
                output
                    .append("  ")
                    .append(mermaidId(dep))
                    .append(" --> ")
                    .append(mermaidId(phase.name()))
                    .append(System.lineSeparator()));
  }

  private String mermaidNode(String phaseName) {
    return mermaidId(phaseName) + "[\"" + escapeLabel(phaseName) + "\"]" + System.lineSeparator();
  }

  private String mermaidId(String value) {
    return "p_" + value.replaceAll("[^A-Za-z0-9_]", "_");
  }

  private String dotGraph(ExecutionPlan plan) {
    var output = new StringBuilder();
    output.append("digraph fluxion {").append(System.lineSeparator());
    output.append("  rankdir=LR;").append(System.lineSeparator());
    plan.phases().forEach(phase -> output.append("  ").append(dotNode(phase.name())));
    plan.phases().forEach(phase -> appendDotEdges(output, phase));
    output.append("}").append(System.lineSeparator());
    return output.toString();
  }

  private void appendDotEdges(StringBuilder output, ExecutionPlan.Phase phase) {
    phase
        .dependsOn()
        .forEach(
            dep ->
                output
                    .append("  \"")
                    .append(escapeLabel(dep))
                    .append("\" -> \"")
                    .append(escapeLabel(phase.name()))
                    .append("\";")
                    .append(System.lineSeparator()));
  }

  private String dotNode(String phaseName) {
    return "\"" + escapeLabel(phaseName) + "\";" + System.lineSeparator();
  }

  private String escapeLabel(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private enum GraphFormat {
    MERMAID,
    DOT,
    JSON
  }
}
