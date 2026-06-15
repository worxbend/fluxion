package dev.sysboot.executor;

import java.util.List;
import java.util.Objects;

public record ValidationReport(
    String profileName, int phaseCount, int moduleCount, List<ValidationIssue> issues) {

  public ValidationReport {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(issues);
    issues = List.copyOf(issues);
  }

  public boolean hasErrors() {
    return issues.stream().anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR);
  }

  public boolean hasWarnings() {
    return issues.stream().anyMatch(issue -> issue.severity() == ValidationIssue.Severity.WARNING);
  }
}
