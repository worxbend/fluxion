package dev.sysboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sysboot.config.yaml.contract.PackageActionDocument;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class WorkstationPackageFileValidator {

  private static final Pattern SDKMAN_VALUE = Pattern.compile("[A-Za-z0-9._+-]+");

  private final WorkstationValidationSupport support;

  WorkstationPackageFileValidator(WorkstationValidationSupport support) {
    this.support = support;
  }

  void validateSdkmanItems(String path, PlanSpecDocument spec, List<String> errors) {
    if (spec == null || spec.packageItems().isEmpty()) {
      errors.add(path + ".spec.packages must contain at least one item");
      return;
    }
    for (int index = 0; index < spec.packageItems().size(); index++) {
      validateSdkmanItem(
          path + ".spec.packages[" + index + "]", spec.packageItems().get(index), errors);
    }
  }

  void validateAppItems(String path, PlanSpecDocument spec, List<String> errors) {
    if (spec == null || spec.apps().isEmpty() && spec.appIds().isEmpty()) {
      errors.add(path + ".spec.apps must contain at least one item");
      return;
    }
    support.validatePresentItems(path + ".spec.apps", spec.apps(), errors);
    support.validatePresentItems(path + ".spec.appIds", spec.appIds(), errors);
  }

  void validatePackageActions(
      PlanKinds.PlanKind kind,
      String path,
      String entryName,
      PlanSpecDocument spec,
      List<String> errors) {
    for (int index = 0; index < spec.actions().size(); index++) {
      validatePackageAction(
          kind.id(),
          path,
          entryName,
          kind.packageActions(),
          spec.actions().get(index),
          index,
          errors);
    }
  }

  void validateAurPackageManager(String path, PlanSpecDocument spec, List<String> errors) {
    String rawPackageManager = spec == null ? null : spec.packageManager().orElse(null);
    if (support.isBlank(rawPackageManager)) {
      errors.add(path + ".spec.packageManager must be one of paru, yay");
      return;
    }
    String packageManager = rawPackageManager.strip().toLowerCase(Locale.ROOT);
    if (!Set.of("paru", "yay").contains(packageManager)) {
      errors.add(path + ".spec.packageManager unsupported AUR helper '" + rawPackageManager + "'");
    }
  }

  void validateBinarySpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    support.requirePresent(
        path + ".spec.binaryName", spec.binaryName().orElse(null), entryName, errors);
    support.validateHttpsUrl(path + ".spec.url", spec.url().orElse(null), errors);
    support.validateAbsolutePath(
        path + ".spec.installPath", spec.installPath().orElse(null), errors);
    spec.checksumUrl()
        .ifPresent(url -> support.validateHttpsUrl(path + ".spec.checksumUrl", url, errors));
    spec.signatureUrl()
        .ifPresent(url -> support.validateHttpsUrl(path + ".spec.signatureUrl", url, errors));
    validateBinaryTrust(path, spec, errors);
    if (spec.url().filter(support::isArchiveUrl).isPresent() && spec.archivePath().isEmpty()) {
      errors.add(path + ".spec.archivePath is required for archive downloads");
    }
    spec.archivePath()
        .ifPresent(value -> support.validateArchivePath(path + ".spec.archivePath", value, errors));
    spec.symlinkPath()
        .ifPresent(
            value -> support.validateAbsolutePath(path + ".spec.symlinkPath", value, errors));
    spec.installMode()
        .ifPresent(value -> support.validateFileMode(path + ".spec.mode", value, errors));
    spec.stripComponents().ifPresent(value -> validateStripComponents(path, value, errors));
  }

  void validateFileWriteSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    List<JsonNode> items = spec.fileWriteItems();
    if (items.isEmpty()) {
      validateFileWriteItem(path + ".spec", entryName, null, spec, errors);
      return;
    }
    for (int index = 0; index < items.size(); index++) {
      validateFileWriteItem(
          path + ".spec.files[" + index + "]", entryName, items.get(index), spec, errors);
    }
  }

  private void validateSdkmanItem(String path, JsonNode item, List<String> errors) {
    if (item.isTextual()) {
      validateSdkmanValue(path, "candidate", item.asText(), errors);
      return;
    }
    if (!item.isObject()) {
      errors.add(path + " must be a candidate string or object");
      return;
    }
    validateSdkmanValue(
        path + ".candidate", "candidate", support.text(item, "candidate").orElse(null), errors);
    support
        .text(item, "version")
        .ifPresent(value -> validateSdkmanValue(path + ".version", "version", value, errors));
  }

  private void validateSdkmanValue(String path, String label, String value, List<String> errors) {
    if (support.isBlank(value)) {
      errors.add(path + " SDKMAN " + label + " must not be blank");
      return;
    }
    if (!SDKMAN_VALUE.matcher(value.strip()).matches()) {
      errors.add(path + " SDKMAN " + label + " contains unsafe shell characters");
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
    if (support.isBlank(rawAction)) {
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
    support.validatePresentItems(actionPath + ".args", action.args(), errors);
  }

  private void validateBinaryTrust(String path, PlanSpecDocument spec, List<String> errors) {
    boolean hasLiteralChecksum = spec.checksum().isPresent();
    boolean hasSignature = spec.signatureUrl().isPresent();
    boolean hasSigner = spec.allowedSignerFingerprint().isPresent();
    if (spec.checksum().isPresent() && spec.checksumUrl().isPresent()) {
      errors.add(path + ".spec must declare either checksum or checksumUrl, not both");
    }
    if (!hasLiteralChecksum && !(hasSignature && hasSigner)) {
      errors.add(
          path
              + ".spec must declare a literal SHA-256 checksum or a detached signature with"
              + " allowedSignerFingerprint");
    }
    if (hasSignature != hasSigner) {
      errors.add(
          path
              + ".spec.signatureUrl and .spec.allowedSignerFingerprint must be configured"
              + " together");
    }
    spec.allowedSignerFingerprint()
        .filter(value -> !value.strip().matches("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})"))
        .ifPresent(
            ignored ->
                errors.add(
                    path
                        + ".spec.allowedSignerFingerprint must contain exactly 40 or 64"
                        + " hexadecimal characters"));
  }

  private void validateStripComponents(String path, int value, List<String> errors) {
    if (value < 0) {
      errors.add(path + ".spec.stripComponents must not be negative");
    }
  }

  private void validateFileWriteItem(
      String path, String entryName, JsonNode node, PlanSpecDocument spec, List<String> errors) {
    if (node != null && !node.isObject()) {
      errors.add(path + " for plan entry '" + entryName + "' must be an object");
      return;
    }
    Optional<String> destination = support.text(node, "destination").or(() -> spec.destination());
    support.validateAbsolutePath(path + ".destination", destination.orElse(null), errors);
    validateFileContentSource(path, node, spec, errors);
    support
        .text(node, "source")
        .or(spec::fileSource)
        .ifPresent(value -> support.validateAbsolutePath(path + ".source", value, errors));
    support
        .text(node, "mode")
        .or(spec::installMode)
        .ifPresent(value -> support.validateFileMode(path + ".mode", value, errors));
    support
        .text(node, "owner")
        .or(spec::owner)
        .ifPresent(value -> support.requirePresent(path + ".owner", value, entryName, errors));
    support
        .text(node, "group")
        .or(spec::group)
        .ifPresent(value -> support.requirePresent(path + ".group", value, entryName, errors));
  }

  private void validateFileContentSource(
      String path, JsonNode node, PlanSpecDocument spec, List<String> errors) {
    Optional<JsonNode> content = support.child(node, "content");
    boolean hasContent = content.isPresent() || spec.contentNode().isPresent();
    boolean hasSource = support.text(node, "source").or(spec::fileSource).isPresent();
    if (hasContent == hasSource) {
      errors.add(path + " must define exactly one of content or source");
    }
    content
        .filter(value -> !value.isTextual())
        .ifPresent(ignored -> errors.add(path + ".content must be a string"));
    if (node == null) {
      spec.contentNode()
          .filter(value -> !value.isTextual())
          .ifPresent(ignored -> errors.add(path + ".content must be a string"));
    }
  }
}
