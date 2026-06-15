package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.executor.ProfileLintIssue;
import dev.sysboot.executor.ProfileLintReport;
import dev.sysboot.executor.ProfileLinter;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Prints advisory profile quality findings without changing the host. */
@Command(name = "lint", description = "Score profile quality and safety guardrails")
public final class LintCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Spec private CommandSpec spec;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private OutputFormat format;

  @Override
  public void run() {
    var context = ApplicationContext.create(true);
    var config = context.configLoader().load(options.resolvedConfigFile());
    ProfileLintReport report = new ProfileLinter().lint(config);
    if (format == OutputFormat.JSON) {
      JsonOutput.write(spec.commandLine().getOut(), jsonReport(report));
      return;
    }
    writeText(report, spec.commandLine().getOut());
  }

  private void writeText(ProfileLintReport report, PrintWriter out) {
    out.printf("Profile: %s%n", report.profileName());
    out.printf("Quality score: %d%n", report.score());
    if (report.issues().isEmpty()) {
      out.println("No lint findings.");
      return;
    }
    out.println();
    report
        .issues()
        .forEach(
            issue ->
                out.printf(
                    "%s %-16s %s: %s%n",
                    severity(issue), issue.category(), issue.path(), issue.message()));
  }

  private String severity(ProfileLintIssue issue) {
    return issue.severity().name().toLowerCase(Locale.ROOT);
  }

  private Map<String, Object> jsonReport(ProfileLintReport report) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", report.profileName());
    output.put("score", report.score());
    output.put("issues", report.issues().stream().map(this::jsonIssue).toList());
    return output;
  }

  private Map<String, Object> jsonIssue(ProfileLintIssue issue) {
    var output = new LinkedHashMap<String, Object>();
    output.put("severity", severity(issue));
    output.put("category", issue.category());
    output.put("path", issue.path());
    output.put("message", issue.message());
    return output;
  }
}
