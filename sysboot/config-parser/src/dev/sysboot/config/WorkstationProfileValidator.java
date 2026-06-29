package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.config.yaml.contract.PolicyDocument;
import dev.sysboot.config.yaml.contract.WorkstationChecksumDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class WorkstationProfileValidator {

  private static final String SUPPORTED_API_VERSION = "initkit.io/v1alpha1";
  private static final String SUPPORTED_KIND = "WorkstationProfile";
  private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
  private static final Set<String> PACKAGE_PLAN_KINDS =
      Set.of("apt-packages", "dnf-packages", "pacman-packages", "zypper-packages");
  private static final Set<String> APP_PLAN_KINDS = Set.of("flatpak-packages");
  private static final Set<String> SUPPORTED_PLAN_KINDS =
      Set.of(
          "apt-packages",
          "dnf-packages",
          "pacman-packages",
          "zypper-packages",
          "flatpak-packages",
          "binary-downloads",
          "shell-scripts",
          "nerd-fonts",
          "dotfiles-apply",
          "commands",
          "interrupt");

  private final WorkstationProfileSourceValidator sourceValidator =
      new WorkstationProfileSourceValidator();

  void validate(WorkstationProfileDocument document, Path manifestPath) {
    var errors = new ArrayList<String>();
    validateHeader(document, errors);
    validateMetadata(document, errors);
    document
        .spec()
        .ifPresent(spec -> validateStatePath(spec.policy().orElse(null), manifestPath, errors));
    document
        .spec()
        .ifPresent(spec -> sourceValidator.validate(spec.sources().orElse(null), errors));
    document.spec().ifPresent(spec -> validatePlan(spec.plan(), errors));
    if (!errors.isEmpty()) {
      throw new IllegalArgumentException(String.join("; ", errors));
    }
  }

  private void validateHeader(WorkstationProfileDocument document, List<String> errors) {
    requireExact(document.apiVersion().orElse(null), SUPPORTED_API_VERSION, "apiVersion", errors);
    requireExact(document.kind().orElse(null), SUPPORTED_KIND, "kind", errors);
  }

  private void validateMetadata(WorkstationProfileDocument document, List<String> errors) {
    if (document.metadata().isEmpty()) {
      errors.add("metadata is required");
      return;
    }
    String name = document.metadata().orElseThrow().name().orElse(null);
    if (isBlank(name)) {
      errors.add("metadata.name must not be blank");
    }
  }

  private void validateStatePath(
      PolicyDocument policy, Path manifestPath, List<String> errors) {
    if (policy == null || policy.statePath().isEmpty()) {
      return;
    }
    String rawStatePath = policy.statePath().orElseThrow();
    if (isBlank(rawStatePath)) {
      errors.add("spec.policy.statePath must not be blank");
      return;
    }
    validateStatePath(rawStatePath, manifestPath, errors);
  }

  private void validatePlan(List<PlanEntryDocument> plan, List<String> errors) {
    Map<String, String> names = new LinkedHashMap<>();
    for (int index = 0; index < plan.size(); index++) {
      validatePlanEntry(plan.get(index), index, names, errors);
    }
  }

  private void validatePlanEntry(
      PlanEntryDocument entry,
      int index,
      Map<String, String> names,
      List<String> errors) {
    String path = "spec.plan[%d]".formatted(index);
    validatePlanName(entry.name().orElse(null), path, names, errors);
    entry.kind()
        .ifPresentOrElse(
            kind -> validatePlanKind(kind, path, entry.spec().orElse(null), errors),
            () -> errors.add(path + ".kind is required"));
  }

  private void validatePlanName(
      String name, String path, Map<String, String> names, List<String> errors) {
    if (isBlank(name)) {
      errors.add(path + ".name must not be blank");
      return;
    }
    String normalized = name.strip();
    String previousPath = names.putIfAbsent(normalized, path + ".name");
    if (previousPath != null) {
      errors.add(
          path
              + ".name duplicates plan entry '"
              + normalized
              + "' first declared at "
              + previousPath);
    }
  }

  private void validatePlanKind(
      String rawKind, String path, PlanSpecDocument spec, List<String> errors) {
    if (isBlank(rawKind)) {
      errors.add(path + ".kind must not be blank");
      return;
    }
    String kind = rawKind.strip().toLowerCase(Locale.ROOT);
    if (!SUPPORTED_PLAN_KINDS.contains(kind)) {
      errors.add(path + ".kind unsupported plan kind '" + rawKind + "'");
      return;
    }
    validateInstallList(kind, path, spec, errors);
    if (spec != null) {
      validateChecksum(path + ".spec.checksum", spec.checksum().orElse(null), errors);
    }
  }

  private void validateInstallList(
      String kind, String path, PlanSpecDocument spec, List<String> errors) {
    if (PACKAGE_PLAN_KINDS.contains(kind)) {
      List<String> packages = spec == null ? List.of() : spec.packages();
      validateNonEmptyItems(path + ".spec.packages", packages, errors);
    }
    if (APP_PLAN_KINDS.contains(kind)) {
      validateAppItems(path, spec, errors);
    }
  }

  private void validateAppItems(String path, PlanSpecDocument spec, List<String> errors) {
    if (spec == null || spec.apps().isEmpty() && spec.appIds().isEmpty()) {
      errors.add(path + ".spec.apps must contain at least one item");
      return;
    }
    validatePresentItems(path + ".spec.apps", spec.apps(), errors);
    validatePresentItems(path + ".spec.appIds", spec.appIds(), errors);
  }

  private void validateNonEmptyItems(String path, List<String> values, List<String> errors) {
    if (values.isEmpty()) {
      errors.add(path + " must contain at least one item");
      return;
    }
    validatePresentItems(path, values, errors);
  }

  private void validatePresentItems(String path, List<String> values, List<String> errors) {
    for (int index = 0; index < values.size(); index++) {
      if (isBlank(values.get(index))) {
        errors.add(path + "[" + index + "] must not be blank");
      }
    }
  }

  private void validateChecksum(
      String path, WorkstationChecksumDocument checksum, List<String> errors) {
    if (checksum == null) {
      return;
    }
    validateChecksumAlgorithm(path, checksum.algorithm().orElse(null), errors);
    validateChecksumValue(path, checksum.value().orElse(null), errors);
  }

  private void validateChecksumAlgorithm(String path, String algorithm, List<String> errors) {
    if (isBlank(algorithm)) {
      errors.add(path + ".algorithm is required");
      return;
    }
    String normalized = algorithm.strip().replace("-", "").toLowerCase(Locale.ROOT);
    if (!"sha256".equals(normalized)) {
      errors.add(path + ".algorithm unsupported checksum algorithm '" + algorithm + "'");
    }
  }

  private void validateChecksumValue(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + ".value is required");
    } else if (!SHA_256_HEX.matcher(value.strip()).matches()) {
      errors.add(path + ".value must be a 64-character hexadecimal SHA-256 digest");
    }
  }

  private void requireExact(
      String value, String expected, String path, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + " is required and must be '" + expected + "'");
    } else if (!expected.equals(value.strip())) {
      errors.add(path + " must be '" + expected + "' but was '" + value + "'");
    }
  }

  private void validateStatePath(String rawStatePath, Path manifestPath, List<String> errors) {
    try {
      Path statePath = resolvePath(rawStatePath, manifestPath);
      if (statePath.equals(manifestPath.toAbsolutePath().normalize())) {
        errors.add("spec.policy.statePath must not equal the manifest path");
      }
    } catch (InvalidPathException e) {
      errors.add("spec.policy.statePath is not a valid path: " + e.getInput());
    }
  }

  private Path resolvePath(String rawPath, Path manifestPath) {
    Path path = Path.of(rawPath);
    if (path.isAbsolute()) {
      return path.normalize();
    }
    Path parent = manifestPath.toAbsolutePath().getParent();
    return (parent == null ? path : parent.resolve(path)).normalize();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
