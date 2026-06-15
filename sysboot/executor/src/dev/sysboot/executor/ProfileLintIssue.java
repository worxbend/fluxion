package dev.sysboot.executor;

import java.util.Objects;

public record ProfileLintIssue(Severity severity, String category, String path, String message) {

  public ProfileLintIssue {
    Objects.requireNonNull(severity);
    Objects.requireNonNull(category);
    Objects.requireNonNull(path);
    Objects.requireNonNull(message);
  }

  public enum Severity {
    WARNING,
    INFO
  }
}
