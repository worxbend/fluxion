package dev.sysboot.executor;

import java.util.Objects;

public record ValidationIssue(Severity severity, String path, String message) {

  public ValidationIssue {
    Objects.requireNonNull(severity);
    Objects.requireNonNull(path);
    Objects.requireNonNull(message);
  }

  public enum Severity {
    ERROR,
    WARNING
  }
}
