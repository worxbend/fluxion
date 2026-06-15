package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.executor.ConfigValidator;
import dev.sysboot.executor.ValidationIssue;
import dev.sysboot.executor.ValidationReport;
import java.io.PrintWriter;
import java.util.Locale;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Validates that a YAML profile can be parsed and planned before any changes are made. */
@Command(name = "validate", description = "Validate a config file")
public final class ValidateCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Option(
      names = "--strict",
      description = "Return a configuration error when warnings are present")
  private boolean strict;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private OutputFormat format;

  @Spec private CommandSpec spec;

  @Override
  public void run() {
    var context = ApplicationContext.create(true);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());
    ValidationReport report = new ConfigValidator().validate(config);

    if (format == OutputFormat.JSON) {
      writeJson(report, spec.commandLine().getOut());
    } else {
      writeText(report, spec.commandLine().getOut());
    }

    if (report.hasErrors() || strict && report.hasWarnings()) {
      throw new CliFailureException(ExitCode.CONFIGURATION_ERROR, "Config validation failed");
    }
  }

  private void writeText(ValidationReport report, PrintWriter out) {
    report
        .issues()
        .forEach(
            issue -> out.printf("%s %s: %s%n", severity(issue), issue.path(), issue.message()));
    if (report.hasErrors() || strict && report.hasWarnings()) {
      out.printf(
          "Config has %d issue(s): profile '%s' with %d job(s), %d step(s)%n",
          report.issues().size(), report.profileName(), report.phaseCount(), report.moduleCount());
      return;
    }
    out.printf(
        "Config is valid: profile '%s' with %d job(s), %d step(s)%n",
        report.profileName(), report.phaseCount(), report.moduleCount());
  }

  private String severity(ValidationIssue issue) {
    return issue.severity().name().toLowerCase(Locale.ROOT);
  }

  private void writeJson(ValidationReport report, PrintWriter out) {
    out.printf(
        "{\"profileName\":\"%s\",\"phaseCount\":%d,\"moduleCount\":%d,\"valid\":%s,\"issues\":[",
        escape(report.profileName()),
        report.phaseCount(),
        report.moduleCount(),
        !report.hasErrors());
    for (int i = 0; i < report.issues().size(); i++) {
      if (i > 0) {
        out.print(",");
      }
      writeJsonIssue(report.issues().get(i), out);
    }
    out.println("]}");
  }

  private void writeJsonIssue(ValidationIssue issue, PrintWriter out) {
    out.printf(
        "{\"severity\":\"%s\",\"path\":\"%s\",\"message\":\"%s\"}",
        issue.severity().name(), escape(issue.path()), escape(issue.message()));
  }

  private String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\b", "\\b")
        .replace("\f", "\\f")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t");
  }
}
