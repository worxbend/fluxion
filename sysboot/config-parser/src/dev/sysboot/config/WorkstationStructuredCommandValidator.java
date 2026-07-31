package dev.sysboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sysboot.config.yaml.contract.PlanSpecDocument;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

final class WorkstationStructuredCommandValidator {

  private final WorkstationValidationSupport support;

  WorkstationStructuredCommandValidator(WorkstationValidationSupport support) {
    this.support = support;
  }

  void validateScriptSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    if (spec.scriptItems().isEmpty()) {
      validateScriptItem(path + ".spec", entryName, null, spec, errors);
    } else {
      validateUniqueScriptItemNames(path, entryName, spec.scriptItems(), errors);
      for (int index = 0; index < spec.scriptItems().size(); index++) {
        validateScriptItem(
            path + ".spec.scripts[" + index + "]",
            entryName,
            spec.scriptItems().get(index),
            spec,
            errors);
      }
    }
    spec.workingDir()
        .ifPresent(value -> support.validatePath(path + ".spec.workingDir", value, errors));
    validateCommonStructuredFields(path + ".spec", spec, errors);
  }

  void validateCommandSpec(
      String path, String entryName, PlanSpecDocument spec, List<String> errors) {
    List<JsonNode> commands = spec.commandItems();
    if (commands.isEmpty()) {
      errors.add(path + ".spec.commands must contain at least one item");
      validateCommonStructuredFields(path + ".spec", spec, errors);
      return;
    }
    validateUniqueCommandItemNames(path, entryName, commands, errors);
    for (int index = 0; index < commands.size(); index++) {
      validateCommandItem(path + ".spec.commands[" + index + "]", commands.get(index), errors);
    }
    spec.shell()
        .ifPresent(
            value -> support.requirePresent(path + ".spec.shell", value, "commands", errors));
    spec.workingDir()
        .ifPresent(value -> support.validatePath(path + ".spec.workingDir", value, errors));
    validateCommonStructuredFields(path + ".spec", spec, errors);
  }

  private void validateUniqueScriptItemNames(
      String path, String entryName, List<JsonNode> items, List<String> errors) {
    Map<String, String> names = new LinkedHashMap<>();
    for (int index = 0; index < items.size(); index++) {
      JsonNode item = items.get(index);
      String itemPath = path + ".spec.scripts[" + index + "]";
      Optional<String> declaredName = support.text(item, "name");
      String name = declaredName.orElse(entryName + "[" + index + "]");
      validateUniqueItemName(
          "script", name, declaredName.isPresent() ? itemPath + ".name" : itemPath, names, errors);
    }
  }

  private void validateUniqueCommandItemNames(
      String path, String entryName, List<JsonNode> items, List<String> errors) {
    Map<String, String> names = new LinkedHashMap<>();
    for (int index = 0; index < items.size(); index++) {
      JsonNode item = items.get(index);
      String itemPath = path + ".spec.commands[" + index + "]";
      Optional<String> declaredName = support.text(item, "name");
      String fallback = item.isTextual() ? item.asText() : entryName + "[" + index + "]";
      validateUniqueItemName(
          "command",
          declaredName.orElse(fallback),
          declaredName.isPresent() ? itemPath + ".name" : itemPath,
          names,
          errors);
    }
  }

  private void validateUniqueItemName(
      String kind,
      String name,
      String declarationPath,
      Map<String, String> names,
      List<String> errors) {
    if (support.isBlank(name)) {
      return;
    }
    String normalized = name.strip();
    String previousPath = names.putIfAbsent(normalized, declarationPath);
    if (previousPath != null) {
      errors.add(
          declarationPath
              + " duplicates "
              + kind
              + " item '"
              + normalized
              + "' first declared at "
              + previousPath);
    }
  }

  private void validateScriptItem(
      String path, String entryName, JsonNode node, PlanSpecDocument spec, List<String> errors) {
    if (node == null || node.isNull()) {
      validateScriptSource(
          path,
          entryName,
          spec.script().isPresent(),
          spec.url().orElse(null),
          spec.sha256(),
          errors);
      support.validatePresentItems(path + ".args", spec.args(), errors);
      return;
    }
    if (node.isTextual()) {
      support.requirePresent(path, node.asText(), entryName, errors);
      spec.sha256()
          .ifPresent(ignored -> errors.add(path + ".sha256 is only valid for a remote URL"));
      return;
    }
    if (!node.isObject()) {
      errors.add(path + " must be a script path string or object");
      return;
    }
    boolean hasScript = support.text(node, "script").isPresent();
    String url = support.text(node, "url").orElse(null);
    Optional<String> sha256 = support.text(node, "sha256").or(spec::sha256);
    validateScriptSource(path, entryName, hasScript, url, sha256, errors);
    support
        .text(node, "cwd")
        .or(() -> support.text(node, "workingDir"))
        .ifPresent(value -> support.validatePath(path + ".cwd", value, errors));
    validateAllowedExitCodes(path, node, errors);
    validateTimeout(path, node, errors);
    validateItemEnvironment(path, node, errors);
    validateConfirm(path, node, errors);
  }

  private void validateScriptSource(
      String path,
      String entryName,
      boolean hasScript,
      String url,
      Optional<String> sha256,
      List<String> errors) {
    boolean hasUrl = !support.isBlank(url);
    if (hasScript == hasUrl) {
      errors.add(
          path + " for plan entry '" + entryName + "' must define exactly one of script or url");
    }
    if (hasUrl) {
      support.validateHttpsUrl(path + ".url", url, errors);
      validateSha256(path + ".sha256", sha256.orElse(null), errors);
    } else if (sha256.isPresent()) {
      errors.add(path + ".sha256 is only valid for a remote URL");
    }
  }

  private void validateSha256(String path, String value, List<String> errors) {
    if (support.isBlank(value)) {
      errors.add(path + " is required for a remote script");
    } else if (!value.strip().matches("[0-9a-fA-F]{64}")) {
      errors.add(path + " must be a 64-character hexadecimal SHA-256 digest");
    }
  }

  private void validateCommandItem(String path, JsonNode node, List<String> errors) {
    if (node.isTextual()) {
      support.requirePresent(path, node.asText(), "commands", errors);
      return;
    }
    if (node.isArray()) {
      support.validateStringArray(path, node, errors);
      return;
    }
    if (!node.isObject()) {
      errors.add(path + " must be a command string, argv array, or object");
      return;
    }
    boolean hasShellRun =
        support.text(node, "run").isPresent() || support.text(node, "shellCommand").isPresent();
    boolean hasArgv =
        support.array(node, "run")
            || support.array(node, "argv")
            || support.text(node, "command").isPresent();
    if (hasShellRun == hasArgv) {
      errors.add(path + " must define exactly one shell string or direct argv command");
    }
    support
        .text(node, "cwd")
        .or(() -> support.text(node, "workingDir"))
        .ifPresent(value -> support.validatePath(path + ".cwd", value, errors));
    validateAllowedExitCodes(path, node, errors);
    validateTimeout(path, node, errors);
    validateItemEnvironment(path, node, errors);
    validateConfirm(path, node, errors);
  }

  private void validateConfirm(String path, JsonNode node, List<String> errors) {
    JsonNode confirm = node.get("confirm");
    if (confirm == null || confirm.isNull()) {
      return;
    }
    if (confirm.isBoolean() || (confirm.isTextual() && !confirm.asText().isBlank())) {
      return;
    }
    errors.add(path + ".confirm must be a boolean or non-blank string");
  }

  private void validateCommonStructuredFields(
      String path, PlanSpecDocument spec, List<String> errors) {
    spec.envNode().ifPresent(env -> validateEnvironment(path + ".env", env, errors));
    spec.creates().ifPresent(value -> support.validatePath(path + ".creates", value, errors));
    spec.timeout().ifPresent(value -> validateDuration(path + ".timeout", value, errors));
    for (Integer exitCode : spec.allowedExitCodes()) {
      if (exitCode < 0) {
        errors.add(path + ".allowedExitCodes must not contain negative values");
      }
    }
  }

  private void validateItemEnvironment(String path, JsonNode node, List<String> errors) {
    if (node.has("env")) {
      validateEnvironment(path + ".env", node.get("env"), errors);
    }
  }

  private void validateEnvironment(String path, JsonNode environment, List<String> errors) {
    if (!environment.isObject()) {
      errors.add(path + " must be an object");
      return;
    }
    for (Map.Entry<String, JsonNode> field : environment.properties()) {
      validateEnvironmentVariable(path + "." + field.getKey(), field.getValue(), errors);
    }
  }

  private void validateEnvironmentVariable(String path, JsonNode value, List<String> errors) {
    if (value.isTextual()) {
      return;
    }
    if (!value.isObject()) {
      errors.add(path + " must be a string or object");
      return;
    }
    JsonNode rawValue = value.get("value");
    if (rawValue == null || rawValue.isNull()) {
      errors.add(path + ".value is required");
    } else if (!rawValue.isTextual()) {
      errors.add(path + ".value must be a string");
    }
    if (value.has("sensitive") && !value.get("sensitive").isBoolean()) {
      errors.add(path + ".sensitive must be a boolean");
    }
  }

  private void validateAllowedExitCodes(String path, JsonNode node, List<String> errors) {
    support
        .child(node, "allowedExitCodes")
        .filter(JsonNode::isArray)
        .ifPresent(
            values ->
                values.forEach(
                    value -> {
                      if (!value.canConvertToInt() || value.asInt() < 0) {
                        errors.add(path + ".allowedExitCodes must contain non-negative integers");
                      }
                    }));
  }

  private void validateTimeout(String path, JsonNode node, List<String> errors) {
    support
        .text(node, "timeout")
        .ifPresent(value -> validateDuration(path + ".timeout", value, errors));
    support
        .child(node, "timeoutSeconds")
        .filter(value -> !value.canConvertToInt() || value.asInt() <= 0)
        .ifPresent(ignored -> errors.add(path + ".timeoutSeconds must be a positive integer"));
  }

  private void validateDuration(String path, String value, List<String> errors) {
    try {
      java.time.Duration parsed = duration(value);
      long nanos = parsed.toNanos();
      if (nanos <= 0) {
        errors.add(path + " must be positive");
      }
    } catch (RuntimeException e) {
      errors.add(path + " is not a supported duration");
    }
  }

  private java.time.Duration duration(String raw) {
    String value = raw.strip().toLowerCase(Locale.ROOT);
    if (value.matches("\\d+")) {
      return java.time.Duration.ofSeconds(Long.parseLong(value));
    }
    if (value.endsWith("ms")) {
      return java.time.Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
    }
    if (value.endsWith("s")) {
      return java.time.Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
    }
    if (value.endsWith("m")) {
      return java.time.Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
    }
    return java.time.Duration.parse(raw);
  }
}
