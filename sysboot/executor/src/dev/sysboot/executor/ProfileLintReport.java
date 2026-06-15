package dev.sysboot.executor;

import java.util.List;
import java.util.Objects;

public record ProfileLintReport(String profileName, int score, List<ProfileLintIssue> issues) {

  public ProfileLintReport {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(issues);
    issues = List.copyOf(issues);
  }
}
