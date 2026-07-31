package dev.sysboot.cli.command;

import dev.sysboot.cli.error.CliFailureException;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.executor.ConfigValidator;
import dev.sysboot.executor.ValidationIssue;
import java.util.stream.Collectors;

final class SemanticConfigValidation {

  private SemanticConfigValidation() {}

  static void requireValid(BootstrapConfig config) {
    var report = new ConfigValidator().validate(config);
    if (!report.hasErrors()) {
      return;
    }
    String details =
        report.issues().stream()
            .filter(issue -> issue.severity() == ValidationIssue.Severity.ERROR)
            .map(issue -> issue.path() + ": " + issue.message())
            .collect(Collectors.joining("; "));
    throw new CliFailureException(
        ExitCode.CONFIGURATION_ERROR, "Config validation failed: " + details);
  }
}
