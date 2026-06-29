package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.PlanEntryDocument;
import dev.sysboot.config.yaml.contract.PackageActionDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import dev.sysboot.config.yaml.contract.PolicyDocument;
import dev.sysboot.config.yaml.contract.WorkstationChecksumDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import java.net.URI;
import java.net.URISyntaxException;
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
  private static final Set<String> INSTALLER_PLAN_KINDS =
      Set.of("binary-downloads", "shell-scripts", "commands", "nerd-fonts", "dotfiles-apply");
  private static final Map<String, Set<String>> SUPPORTED_PACKAGE_ACTIONS =
      Map.of(
          "apt-packages", Set.of("update", "upgrade", "dist-upgrade"),
          "dnf-packages",
              Set.of("check-update", "upgrade", "swap", "groupupdate", "group-update"),
          "pacman-packages", Set.of("sync-upgrade", "syu", "upgrade"),
          "zypper-packages", Set.of("refresh", "update", "dup", "dup-from"));
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
            kind ->
                validatePlanKind(
                    kind, path, entry.name().orElse("<unnamed>"), entry.spec().orElse(null), errors),
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
      String rawKind, String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (isBlank(rawKind)) {
      errors.add(path + ".kind must not be blank");
      return;
    }
    String kind = rawKind.strip().toLowerCase(Locale.ROOT);
    if (!SUPPORTED_PLAN_KINDS.contains(kind)) {
      errors.add(path + ".kind unsupported plan kind '" + rawKind + "'");
      return;
    }
    validateInstallList(kind, path, entryName, spec, errors);
    validateInstallerSpec(kind, path, entryName, spec, errors);
    validatePackageActions(kind, path, entryName, spec, errors);
    if (spec != null) {
      validateChecksum(path + ".spec.checksum", spec.checksum().orElse(null), errors);
    }
  }

  private void validateInstallList(
      String kind, String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (PACKAGE_PLAN_KINDS.contains(kind)) {
      List<String> packages = spec == null ? List.of() : spec.packages();
      validateNonEmptyItems(path + ".spec.packages", packages, errors);
    }
    if (APP_PLAN_KINDS.contains(kind)) {
      validateAppItems(path, spec, errors);
    }
  }

  private void validatePackageActions(
      String kind, String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (!PACKAGE_PLAN_KINDS.contains(kind) || spec == null) {
      return;
    }
    Set<String> supported = SUPPORTED_PACKAGE_ACTIONS.get(kind);
    for (int index = 0; index < spec.actions().size(); index++) {
      validatePackageAction(kind, path, entryName, supported, spec.actions().get(index), index, errors);
    }
  }

  private void validatePackageAction(
      String kind,
      String path,
      String entryName,
      Set<String> supported,
      PackageActionDocument action,
      int index,
      List<String> errors) {
    String actionPath = path + ".spec.actions[" + index + "]";
    String rawAction = action.action().orElse(null);
    if (isBlank(rawAction)) {
      errors.add(actionPath + ".action for plan entry '" + entryName + "' must not be blank");
      return;
    }
    String normalized = rawAction.strip().toLowerCase(Locale.ROOT);
    if (!supported.contains(normalized)) {
      errors.add(
          actionPath
              + ".action for plan entry '"
              + entryName
              + "' unsupported action '"
              + rawAction
              + "' for "
              + kind);
    }
    validatePresentItems(actionPath + ".args", action.args(), errors);
  }

  private void validateAppItems(String path, PlanSpecDocument spec, List<String> errors) {
    if (spec == null || spec.apps().isEmpty() && spec.appIds().isEmpty()) {
      errors.add(path + ".spec.apps must contain at least one item");
      return;
    }
    validatePresentItems(path + ".spec.apps", spec.apps(), errors);
    validatePresentItems(path + ".spec.appIds", spec.appIds(), errors);
  }

  private void validateInstallerSpec(
      String kind, String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (!INSTALLER_PLAN_KINDS.contains(kind)) {
      return;
    }
    if (spec == null) {
      errors.add(path + ".spec is required for plan entry '" + entryName + "'");
      return;
    }
    switch (kind) {
      case "binary-downloads" -> validateBinarySpec(path, entryName, spec, errors);
      case "shell-scripts" -> validateScriptSpec(path, entryName, spec, errors);
      case "commands" -> validateCommandSpec(path, spec, errors);
      case "nerd-fonts" -> validateNerdFontSpec(path, entryName, spec, errors);
      case "dotfiles-apply" -> validateDotfilesSpec(path, entryName, spec, errors);
      default -> {
      }
    }
  }

  private void validateBinarySpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    requirePresent(path + ".spec.binaryName", spec.binaryName().orElse(null), entryName, errors);
    validateHttpsUrl(path + ".spec.url", spec.url().orElse(null), errors);
    validateAbsolutePath(path + ".spec.installPath", spec.installPath().orElse(null), errors);
    spec.checksumUrl().ifPresent(url -> validateHttpsUrl(path + ".spec.checksumUrl", url, errors));
    spec.signatureUrl().ifPresent(url -> validateHttpsUrl(path + ".spec.signatureUrl", url, errors));
  }

  private void validateScriptSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    requirePresent(path + ".spec.script", spec.script().orElse(null), entryName, errors);
    validatePresentItems(path + ".spec.args", spec.args(), errors);
    spec.workingDir().ifPresent(dir -> validatePath(path + ".spec.workingDir", dir, errors));
  }

  private void validateCommandSpec(String path, PlanSpecDocument spec, List<String> errors) {
    validateNonEmptyItems(path + ".spec.commands", spec.commands(), errors);
    spec.shell().ifPresent(shell -> requirePresent(path + ".spec.shell", shell, "commands", errors));
    spec.workingDir().ifPresent(dir -> validatePath(path + ".spec.workingDir", dir, errors));
  }

  private void validateNerdFontSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    validateNerdFontConfigShape(path, spec, errors);
    requirePresent(
        path + ".spec.installerVersion",
        spec.installerVersion().orElse("v1.0.5"),
        entryName,
        errors);
    requirePresent(
        path + ".spec.nerdfontBinary",
        spec.nerdfontBinary().orElse("nerdfont-install"),
        entryName,
        errors);
    validateNonEmptyItems(nerdFontFamiliesPath(path, spec), nerdFontFamilies(spec), errors);
  }

  private void validateDotfilesSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.configIsObject()) {
      errors.add(path + ".spec.config for plan entry '" + entryName + "' must be a path string");
    }
    requirePresent(path + ".spec.config", spec.dotfilesConfig().orElse(null), entryName, errors);
    requirePresent(
        path + ".spec.installerVersion",
        spec.installerVersion().orElse("v0.2.1"),
        entryName,
        errors);
    requirePresent(
        path + ".spec.dotbotBinary",
        spec.dotbotBinary().orElse("dotbot"),
        entryName,
        errors);
  }

  private void validateNerdFontConfigShape(
      String path, PlanSpecDocument spec, List<String> errors) {
    if (spec.configIsText()) {
      errors.add(path + ".spec.config must be an object for nerd-fonts");
    }
    spec.destination()
        .ifPresent(value -> requirePresent(path + ".spec.destination", value, "nerd-fonts", errors));
    spec.release()
        .ifPresent(value -> requirePresent(path + ".spec.release", value, "nerd-fonts", errors));
    spec.nerdFontConfig().ifPresent(config -> validateNerdFontConfig(path, config, errors));
  }

  private void validateNerdFontConfig(
      String path,
      dev.sysboot.config.yaml.contract.NerdFontConfigDocument config,
      List<String> errors) {
    requirePresent(path + ".spec.config.release", config.release, "nerd-fonts", errors);
    if (config.destination != null) {
      requirePresent(path + ".spec.config.destination", config.destination, "nerd-fonts", errors);
    }
  }

  private List<String> nerdFontFamilies(PlanSpecDocument spec) {
    return spec.nerdFontConfig().map(config -> config.families).orElseGet(spec::families);
  }

  private String nerdFontFamiliesPath(String path, PlanSpecDocument spec) {
    return spec.nerdFontConfig().isPresent()
        ? path + ".spec.config.families"
        : path + ".spec.families";
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

  private void requirePresent(
      String path, String value, String entryName, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + " for plan entry '" + entryName + "' must not be blank");
    }
  }

  private void validateAbsolutePath(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + " is required");
      return;
    }
    try {
      Path parsed = Path.of(expandHome(value));
      if (!parsed.isAbsolute()) {
        errors.add(path + " must be absolute");
      }
    } catch (InvalidPathException e) {
      errors.add(path + " is not a valid path: " + e.getInput());
    }
  }

  private void validatePath(String path, String value, List<String> errors) {
    try {
      Path.of(expandHome(value));
    } catch (InvalidPathException e) {
      errors.add(path + " is not a valid path: " + e.getInput());
    }
  }

  private void validateHttpsUrl(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + " is required");
      return;
    }
    try {
      URI uri = new URI(value);
      if (!"https".equalsIgnoreCase(uri.getScheme())) {
        errors.add(path + " must use https");
      }
    } catch (URISyntaxException e) {
      errors.add(path + " is not a valid URI: " + value);
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

  private String expandHome(String rawPath) {
    if (rawPath.equals("~")) {
      return System.getProperty("user.home");
    }
    return rawPath.startsWith("~/")
        ? System.getProperty("user.home") + rawPath.substring(1)
        : rawPath;
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
