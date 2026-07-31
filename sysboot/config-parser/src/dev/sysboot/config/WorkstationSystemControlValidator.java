package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class WorkstationSystemControlValidator {

  private final WorkstationValidationSupport support;

  WorkstationSystemControlValidator(WorkstationValidationSupport support) {
    this.support = support;
  }

  void validateInterruptSpec(String path, PlanSpecDocument spec, List<String> errors) {
    if (spec == null) {
      return;
    }
    spec.message()
        .ifPresent(
            value -> support.requirePresent(path + ".spec.message", value, "interrupt", errors));
    support.validatePresentItems(path + ".spec.instructions", spec.instructions(), errors);
    spec.resumeFrom().ifPresent(value -> validateResumeFrom(path, value, errors));
    spec.exitCode().ifPresent(value -> validateExitCode(path, value, errors));
  }

  void validateUserGroupsSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.groups().isEmpty()) {
      errors.add(path + ".spec.groups is required for plan entry '" + entryName + "'");
    }
    if (spec.groups().stream().distinct().count() != spec.groups().size()) {
      errors.add(path + ".spec.groups for plan entry '" + entryName + "' repeats a group");
    }
    spec.groups().stream()
        .filter(group -> group.startsWith("-") || group.startsWith("!"))
        .forEach(
            group ->
                errors.add(
                    path
                        + ".spec.groups for plan entry '"
                        + entryName
                        + "' contains '"
                        + group
                        + "'. Group membership is append-only; Fluxion never removes a user from a"
                        + " group. Use gpasswd -d by hand."));
  }

  void validateGitConfigSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.entries().isEmpty()) {
      errors.add(path + ".spec.entries is required for plan entry '" + entryName + "'");
    }
    spec.entries().keySet().stream()
        .filter(key -> !key.contains("."))
        .forEach(
            key ->
                errors.add(
                    path
                        + ".spec.entries for plan entry '"
                        + entryName
                        + "' has key '"
                        + key
                        + "'; git config keys are section.key, for example user.email"));
  }

  void validateGitRepoSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.repos().isEmpty()) {
      errors.add(path + ".spec.repos is required for plan entry '" + entryName + "'");
    }
    spec.repos()
        .forEach(
            repo -> {
              support.requirePresent(path + ".spec.repos[].url", repo.url, entryName, errors);
              support.requirePresent(path + ".spec.repos[].dest", repo.dest, entryName, errors);
            });
  }

  void validateSystemdUnitSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.units().isEmpty()) {
      errors.add(path + ".spec.units is required for plan entry '" + entryName + "'");
    }
    spec.units()
        .forEach(
            unit -> {
              support.requirePresent(path + ".spec.units[].name", unit.name, entryName, errors);
              if (unit.mask && unit.enabled) {
                errors.add(
                    path
                        + ".spec.units[] for plan entry '"
                        + entryName
                        + "' cannot both mask and enable '"
                        + unit.name
                        + "'");
              }
            });
  }

  void validateSystemSettingSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    boolean empty =
        spec.localRtc().isEmpty()
            && spec.ntp().isEmpty()
            && spec.timezone().isEmpty()
            && spec.hostname().isEmpty()
            && spec.locale().isEmpty();
    if (empty) {
      errors.add(
          path + ".spec for plan entry '" + entryName + "' declares no system setting to apply");
    }
  }

  void validateSystemUpdateSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    support.requirePresent(
        path + ".spec.packageManager", spec.packageManager().orElse(null), entryName, errors);
    spec.packageManager()
        .map(value -> value.strip().toUpperCase(Locale.ROOT))
        .filter(value -> Set.of("CARGO", "FLATPAK").contains(value))
        .ifPresent(
            value ->
                errors.add(
                    path
                        + ".spec.packageManager does not support system-update: "
                        + value.toLowerCase(Locale.ROOT)));
    if (spec.distUpgrade() && spec.refreshOnly()) {
      errors.add(
          path
              + ".spec for plan entry '"
              + entryName
              + "' cannot be both distUpgrade and refreshOnly");
    }
  }

  private void validateResumeFrom(String path, String value, List<String> errors) {
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    if (!Set.of("current", "next").contains(normalized)) {
      errors.add(path + ".spec.resumeFrom must be either current or next");
    }
  }

  private void validateExitCode(String path, int value, List<String> errors) {
    if (value < 0 || value > 255) {
      errors.add(path + ".spec.exitCode must be between 0 and 255");
    }
  }
}
