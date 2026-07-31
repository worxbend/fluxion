package dev.sysboot.cli.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PublicUrl;
import dev.sysboot.core.StateEntry;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.PhaseExecutionPlanner;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "report",
    description = "Render persisted run reports",
    subcommands = {ReportCommand.LastSubcommand.class})
public final class ReportCommand implements Runnable {

  @Override
  public void run() {
    System.out.println("Run 'fluxion report --help' for subcommands.");
  }

  @Command(name = "last", description = "Render a report from the latest persisted state")
  public static final class LastSubcommand implements Runnable {

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
        defaultValue = "markdown",
        description = "Output format: markdown or html")
    private String format;

    @Override
    public void run() {
      BootstrapState state =
          new JsonStateRepository(new ObjectMapper())
              .load(profile)
              .orElseThrow(
                  () ->
                      new CliFailureException(
                          ExitCode.CONFIGURATION_ERROR,
                          "No state file found for profile: " + profile));
      Optional<String> resumeCommand = resumeCommand(state);
      PrintWriter out = spec.commandLine().getOut();
      switch (format.toLowerCase(Locale.ROOT)) {
        case "markdown" -> writeMarkdown(state, resumeCommand, out);
        case "html" -> writeHtml(state, resumeCommand, out);
        default ->
            throw new CliFailureException(
                ExitCode.INVALID_INPUT, "Unsupported report format: " + format);
      }
    }

    private Optional<String> resumeCommand(BootstrapState state) {
      if (!options.hasConfigFile() || !Files.isReadable(options.resolvedConfigFile())) {
        return Optional.empty();
      }
      BootstrapConfig config;
      try (var context = ApplicationContext.create(true)) {
        config = context.configLoader().load(options.resolvedConfigFile());
      }
      Optional<String> phase = resumePhase(config, state);
      return phase.map(
          ignored -> ResumeCommandFormatter.command(options.resolvedConfigFile(), profile, phase));
    }

    private Optional<String> resumePhase(BootstrapConfig config, BootstrapState state) {
      return state
          .nextPlanEntry()
          .flatMap(next -> phaseContaining(config, next))
          .or(() -> nextIncompletePhase(config, state));
    }

    private Optional<String> phaseContaining(BootstrapConfig config, String moduleName) {
      return config.phases().stream()
          .filter(
              phase ->
                  phase.modules().stream()
                      .anyMatch(module -> module.name().value().equals(moduleName)))
          .map(phase -> phase.name().value())
          .findFirst();
    }

    private Optional<String> nextIncompletePhase(BootstrapConfig config, BootstrapState state) {
      return new PhaseExecutionPlanner()
          .plan(config.phases()).stream()
              .filter(phase -> !state.isPhaseCompleted(phase.name().value()))
              .map(phase -> phase.name().value())
              .findFirst();
    }

    private void writeMarkdown(
        BootstrapState state, Optional<String> resumeCommand, PrintWriter out) {
      out.println("# Fluxion Run Report");
      out.println();
      out.printf("- Profile: `%s`%n", markdown(state.profileName()));
      out.printf("- Last run: `%s`%n", state.lastRunAt());
      out.printf("- State version: `%s`%n", markdown(state.sysbootVersion()));
      if (options.hasConfigFile()) {
        out.printf("- Config: `%s`%n", markdown(options.resolvedConfigFile().toString()));
      }
      out.println();
      writeMarkdownResume(resumeCommand, out);
      writeMarkdownPhases(state, out);
      writeMarkdownItems(state, out);
    }

    private void writeMarkdownResume(Optional<String> resumeCommand, PrintWriter out) {
      out.println("## Resume");
      out.println();
      if (resumeCommand.isPresent()) {
        out.printf("`%s`%n", markdown(resumeCommand.orElseThrow()));
      } else {
        out.println("No resume command available from the current state and config.");
      }
      out.println();
    }

    private void writeMarkdownPhases(BootstrapState state, PrintWriter out) {
      out.println("## Phases");
      out.println();
      if (state.phaseEntries().isEmpty()) {
        out.println("_No phase state recorded._");
        out.println();
        return;
      }
      out.println("| Phase | Status | Completed at | Reason |");
      out.println("| --- | --- | --- | --- |");
      state.phaseEntries().forEach(phase -> out.println(markdownPhaseRow(phase)));
      out.println();
    }

    private String markdownPhaseRow(PhaseStateEntry phase) {
      return "| %s | %s | %s | %s |"
          .formatted(
              markdown(phase.phaseName()),
              phase.status().name().toLowerCase(),
              phase.completedAt(),
              markdown(phase.reason().orElse("")));
    }

    private void writeMarkdownItems(BootstrapState state, PrintWriter out) {
      out.println("## Items");
      out.println();
      if (state.entries().isEmpty()) {
        out.println("_No item state recorded._");
        return;
      }
      out.println("| Module | Item | Type | Completed at | Version | Checksum | Source |");
      out.println("| --- | --- | --- | --- | --- | --- | --- |");
      state.entries().forEach(entry -> out.println(markdownItemRow(entry)));
    }

    private String markdownItemRow(StateEntry entry) {
      return "| %s | %s | %s | %s | %s | %s | %s |"
          .formatted(
              markdown(entry.moduleName()),
              markdown(entry.itemKey()),
              entry.itemType().name().toLowerCase(),
              entry.completedAt(),
              markdown(entry.version().orElse("")),
              markdown(entry.checksum().orElse("")),
              markdown(entry.sourceUrl().map(PublicUrl::from).orElse("")));
    }

    private void writeHtml(BootstrapState state, Optional<String> resumeCommand, PrintWriter out) {
      out.println("<!doctype html>");
      out.println("<html><head><meta charset=\"utf-8\"><title>Fluxion Run Report</title></head>");
      out.println("<body>");
      out.println("<h1>Fluxion Run Report</h1>");
      out.printf("<p><strong>Profile:</strong> %s</p>%n", html(state.profileName()));
      out.printf("<p><strong>Last run:</strong> %s</p>%n", state.lastRunAt());
      out.printf("<p><strong>State version:</strong> %s</p>%n", html(state.sysbootVersion()));
      resumeCommand.ifPresent(
          command ->
              out.printf("<p><strong>Resume:</strong> <code>%s</code></p>%n", html(command)));
      writeHtmlPhases(state, out);
      writeHtmlItems(state, out);
      out.println("</body></html>");
    }

    private void writeHtmlPhases(BootstrapState state, PrintWriter out) {
      out.println(
          "<h2>Phases</h2><table><tr><th>Phase</th><th>Status</th><th>Completed</th><th>Reason</th></tr>");
      if (state.phaseEntries().isEmpty()) {
        out.println("<tr><td colspan=\"4\">No phase state recorded.</td></tr>");
      }
      state
          .phaseEntries()
          .forEach(
              p ->
                  out.printf(
                      "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>%n",
                      html(p.phaseName()),
                      p.status().name().toLowerCase(Locale.ROOT),
                      p.completedAt(),
                      html(p.reason().orElse(""))));
      out.println("</table>");
    }

    private void writeHtmlItems(BootstrapState state, PrintWriter out) {
      out.println(
          "<h2>Items</h2><table><tr><th>Module</th><th>Item</th><th>Type</th>"
              + "<th>Completed</th><th>Version</th><th>Checksum</th><th>Source</th></tr>");
      if (state.entries().isEmpty()) {
        out.println("<tr><td colspan=\"7\">No item state recorded.</td></tr>");
      }
      state
          .entries()
          .forEach(
              e ->
                  out.printf(
                      "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>%n",
                      html(e.moduleName()),
                      html(e.itemKey()),
                      e.itemType().name().toLowerCase(Locale.ROOT),
                      e.completedAt(),
                      html(e.version().orElse("")),
                      html(e.checksum().orElse("")),
                      html(e.sourceUrl().map(PublicUrl::from).orElse(""))));
      out.println("</table>");
    }

    private String markdown(String value) {
      return value.replace("|", "\\|").replace("\n", " ");
    }

    private String html(String value) {
      return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
  }
}
