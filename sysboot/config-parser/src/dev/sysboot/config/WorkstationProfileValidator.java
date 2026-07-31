package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.config.yaml.contract.PolicyDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class WorkstationProfileValidator {

  private static final String SUPPORTED_API_VERSION = "initkit.io/v1alpha1";
  private static final String SUPPORTED_KIND = "WorkstationProfile";

  private final WorkstationValidationSupport support;
  private final WorkstationProfileSourceValidator sourceValidator;
  private final WorkstationPlanValidators planValidators;

  WorkstationProfileValidator() {
    this(new WorkstationValidationSupport(), new WorkstationProfileSourceValidator());
  }

  WorkstationProfileValidator(
      WorkstationValidationSupport support, WorkstationProfileSourceValidator sourceValidator) {
    this.support = support;
    this.sourceValidator = sourceValidator;
    this.planValidators = new WorkstationPlanValidators(support);
  }

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
    if (support.isBlank(name)) {
      errors.add("metadata.name must not be blank");
    }
  }

  private void validateStatePath(PolicyDocument policy, Path manifestPath, List<String> errors) {
    if (policy == null || policy.statePath().isEmpty()) {
      return;
    }
    String rawStatePath = policy.statePath().orElseThrow();
    if (support.isBlank(rawStatePath)) {
      errors.add("spec.policy.statePath must not be blank");
      return;
    }
    try {
      Path statePath = resolvePath(rawStatePath, manifestPath);
      if (statePath.equals(manifestPath.toAbsolutePath().normalize())) {
        errors.add("spec.policy.statePath must not equal the manifest path");
      }
    } catch (InvalidPathException e) {
      errors.add("spec.policy.statePath is not a valid path: " + e.getInput());
    }
  }

  private void validatePlan(List<PlanEntryDocument> plan, List<String> errors) {
    Map<String, String> names = new LinkedHashMap<>();
    for (int index = 0; index < plan.size(); index++) {
      validatePlanEntry(plan.get(index), index, names, errors);
    }
  }

  private void validatePlanEntry(
      PlanEntryDocument entry, int index, Map<String, String> names, List<String> errors) {
    String path = "spec.plan[%d]".formatted(index);
    validatePlanName(entry.name().orElse(null), path, names, errors);
    entry
        .kind()
        .ifPresentOrElse(
            kind ->
                validatePlanKind(
                    kind,
                    path,
                    entry.name().orElse("<unnamed>"),
                    entry.spec().orElse(null),
                    errors),
            () -> errors.add(path + ".kind is required"));
  }

  private void validatePlanName(
      String name, String path, Map<String, String> names, List<String> errors) {
    if (support.isBlank(name)) {
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
      String rawKind, String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (support.isBlank(rawKind)) {
      errors.add(path + ".kind must not be blank");
      return;
    }
    Optional<PlanKinds.PlanKind> planKind =
        PlanKinds.find(rawKind.strip().toLowerCase(Locale.ROOT));
    if (planKind.isEmpty()) {
      errors.add(
          path
              + ".kind unsupported plan kind '"
              + rawKind
              + "'"
              + PlanKinds.closestId(rawKind).map(id -> ". Did you mean '" + id + "'?").orElse(""));
      return;
    }
    validateKindShape(planKind.orElseThrow(), path, entryName, spec, errors);
    if (spec != null) {
      support.validateChecksum(path + ".spec.checksum", spec.checksum().orElse(null), errors);
    }
  }

  private void validateKindShape(
      PlanKinds.PlanKind kind,
      String path,
      String entryName,
      PlanSpecDocument spec,
      List<String> errors) {
    switch (kind.category()) {
      case PACKAGES ->
          support.validateNonEmptyItems(
              path + ".spec.packages", spec == null ? List.of() : spec.packages(), errors);
      case APPS -> planValidators.packageFile().validateAppItems(path, spec, errors);
      case SDKMAN -> planValidators.packageFile().validateSdkmanItems(path, spec, errors);
      case INSTALLER -> {
        if (spec == null) {
          errors.add(path + ".spec is required for plan entry '" + entryName + "'");
          return;
        }
      }
      case CONTROL -> {}
    }
    kind.specCheck().check(planValidators, path, entryName, spec, errors);
    if (kind.category() == PlanKinds.Category.PACKAGES && spec != null) {
      planValidators.packageFile().validatePackageActions(kind, path, entryName, spec, errors);
    }
  }

  private void requireExact(String value, String expected, String path, List<String> errors) {
    if (support.isBlank(value)) {
      errors.add(path + " is required and must be '" + expected + "'");
    } else if (!expected.equals(value.strip())) {
      errors.add(path + " must be '" + expected + "' but was '" + value + "'");
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
}
