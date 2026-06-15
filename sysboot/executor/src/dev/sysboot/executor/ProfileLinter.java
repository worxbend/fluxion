package dev.sysboot.executor;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.ShellCommandModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProfileLinter {

  public ProfileLintReport lint(BootstrapConfig config) {
    var issues = new ArrayList<ProfileLintIssue>();
    for (int phaseIndex = 0; phaseIndex < config.phases().size(); phaseIndex++) {
      Phase phase = config.phases().get(phaseIndex);
      lintPhase(phase, phaseIndex, issues);
    }
    return new ProfileLintReport(config.profileName().value(), score(issues), issues);
  }

  private void lintPhase(Phase phase, int phaseIndex, List<ProfileLintIssue> issues) {
    for (int moduleIndex = 0; moduleIndex < phase.modules().size(); moduleIndex++) {
      BootstrapModule module = phase.modules().get(moduleIndex);
      String path = "jobs[%d].steps[%d]".formatted(phaseIndex, moduleIndex);
      lintModule(module, path, issues);
    }
  }

  private void lintModule(BootstrapModule module, String path, List<ProfileLintIssue> issues) {
    switch (module) {
      case CompiledBinaryModule binary -> lintBinary(binary, path, issues);
      case ShellCommandModule shell -> lintShellCommands(shell, path, issues);
      default -> {}
    }
  }

  private void lintBinary(CompiledBinaryModule module, String path, List<ProfileLintIssue> issues) {
    if (module.checksum().isEmpty() && module.checksumUrl().isEmpty()) {
      warning(
          issues,
          "safety",
          path + ".checksum",
          "Compiled binary '%s' should declare a checksum".formatted(module.name().value()));
    }
    if (module.expectedVersion().isEmpty()) {
      info(
          issues,
          "reproducibility",
          path + ".expectedVersion",
          "Compiled binary '%s' has no expected version".formatted(module.name().value()));
    }
  }

  private void lintShellCommands(
      ShellCommandModule module, String path, List<ProfileLintIssue> issues) {
    if (module.probeCommand().isEmpty()) {
      warning(
          issues,
          "recoverability",
          path + ".probeCommand",
          "Shell command module '%s' should declare a probe command"
              .formatted(module.name().value()));
    }
    for (int index = 0; index < module.commands().size(); index++) {
      lintShellCommand(
          module.commands().get(index), path + ".commands[%d]".formatted(index), issues);
    }
  }

  private void lintShellCommand(String command, String path, List<ProfileLintIssue> issues) {
    String normalized = command.toLowerCase(Locale.ROOT);
    if (normalized.contains("rm -rf") || normalized.contains("mkfs.")) {
      warning(issues, "safety", path, "Potentially destructive shell command needs review");
    }
    if (pipesDownloadedContentIntoShell(normalized)) {
      warning(issues, "safety", path, "Piping downloaded content into a shell needs review");
    }
    if (configuresRepository(normalized)) {
      warning(
          issues,
          "safety",
          path,
          "Repository setup in shell commands is hard to audit; prefer first-class repository"
              + " modules when available");
    }
    if (normalized.contains("sudo ")) {
      info(
          issues,
          "observability",
          path,
          "Shell command embeds sudo instead of using Fluxion sudo handling");
    }
  }

  private boolean pipesDownloadedContentIntoShell(String normalized) {
    return normalized.contains("curl ")
        && (normalized.contains("| sh") || normalized.contains("| bash"));
  }

  private boolean configuresRepository(String normalized) {
    return normalized.contains("dnf config-manager")
        || normalized.contains("add-apt-repository")
        || normalized.contains("apt-key ")
        || normalized.contains("rpm --import")
        || normalized.contains("flatpak remote-add")
        || normalized.contains("zypper addrepo")
        || normalized.contains("pacman-key ");
  }

  private int score(List<ProfileLintIssue> issues) {
    int penalty =
        issues.stream()
            .mapToInt(issue -> issue.severity() == ProfileLintIssue.Severity.WARNING ? 10 : 3)
            .sum();
    return Math.max(0, 100 - penalty);
  }

  private void warning(
      List<ProfileLintIssue> issues, String category, String path, String message) {
    issues.add(new ProfileLintIssue(ProfileLintIssue.Severity.WARNING, category, path, message));
  }

  private void info(List<ProfileLintIssue> issues, String category, String path, String message) {
    issues.add(new ProfileLintIssue(ProfileLintIssue.Severity.INFO, category, path, message));
  }
}
