package dev.sysboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import dev.sysboot.config.yaml.contract.WorkstationChecksumDocument;
import dev.sysboot.core.PublicUrl;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class WorkstationValidationSupport {

  private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-fA-F]{64}");
  private static final Pattern FILE_MODE = Pattern.compile("[0-7]{3,4}");

  void validateNonEmptyItems(String path, List<String> values, List<String> errors) {
    if (values.isEmpty()) {
      errors.add(path + " must contain at least one item");
      return;
    }
    validatePresentItems(path, values, errors);
  }

  void validatePresentItems(String path, List<String> values, List<String> errors) {
    for (int index = 0; index < values.size(); index++) {
      if (isBlank(values.get(index))) {
        errors.add(path + "[" + index + "] must not be blank");
      }
    }
  }

  void requirePresent(String path, String value, String entryName, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + " for plan entry '" + entryName + "' must not be blank");
    }
  }

  void validateAbsolutePath(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + " is required");
      return;
    }
    try {
      Path parsed = Path.of(expandHome(value));
      if (!parsed.isAbsolute()) {
        errors.add(path + " must be absolute");
      } else if (!parsed.equals(parsed.normalize())) {
        errors.add(path + " must be normalized");
      }
    } catch (InvalidPathException e) {
      errors.add(path + " is not a valid path: " + e.getInput());
    }
  }

  void validatePath(String path, String value, List<String> errors) {
    try {
      Path.of(expandHome(value));
    } catch (InvalidPathException e) {
      errors.add(path + " is not a valid path: " + e.getInput());
    }
  }

  void validateHttpsUrl(String path, String value, List<String> errors) {
    if (isBlank(value)) {
      errors.add(path + " is required");
      return;
    }
    try {
      URI uri = new URI(value);
      if (!"https".equalsIgnoreCase(uri.getScheme())) {
        errors.add(path + " must use https");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        errors.add(path + " must include a host");
      }
      if (uri.getUserInfo() != null) {
        errors.add(path + " must not include user-info");
      }
    } catch (URISyntaxException e) {
      errors.add(path + " is not a valid URI: " + PublicUrl.from(value));
    }
  }

  void validateChecksum(String path, WorkstationChecksumDocument checksum, List<String> errors) {
    if (checksum == null) {
      return;
    }
    validateChecksumAlgorithm(path, checksum.algorithm().orElse(null), errors);
    validateChecksumValue(path, checksum.value().orElse(null), errors);
  }

  void validateFileMode(String path, String mode, List<String> errors) {
    if (!FILE_MODE.matcher(mode.strip()).matches()) {
      errors.add(path + " must be a 3 or 4 digit octal mode");
    }
  }

  void validateArchivePath(String path, String value, List<String> errors) {
    if (value.isBlank()) {
      errors.add(path + " must not be blank");
      return;
    }
    try {
      Path parsed = Path.of(value);
      if (parsed.isAbsolute()
          || parsed.startsWith("..")
          || !parsed.equals(parsed.normalize())
          || !value.equals(parsed.toString())
          || value.indexOf('\\') >= 0) {
        errors.add(path + " must be a normalized relative POSIX path");
      }
    } catch (InvalidPathException e) {
      errors.add(path + " is not a valid path: " + e.getInput());
    }
  }

  boolean isArchiveUrl(String value) {
    try {
      String path = new URI(value).getPath().toLowerCase(Locale.ROOT);
      return Set.of(".tar.gz", ".tgz", ".tar.xz", ".zip").stream().anyMatch(path::endsWith);
    } catch (URISyntaxException e) {
      return false;
    }
  }

  Optional<String> text(JsonNode node, String field) {
    return child(node, field)
        .filter(JsonNode::isTextual)
        .map(JsonNode::asText)
        .filter(value -> !value.isBlank());
  }

  Optional<JsonNode> child(JsonNode node, String field) {
    if (node == null || !node.isObject()) {
      return Optional.empty();
    }
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? Optional.empty() : Optional.of(value);
  }

  boolean array(JsonNode node, String field) {
    return child(node, field)
        .filter(JsonNode::isArray)
        .filter(value -> value.size() > 0)
        .isPresent();
  }

  void validateStringArray(String path, JsonNode node, List<String> errors) {
    if (node.isEmpty()) {
      errors.add(path + " must contain at least one item");
    }
    for (int index = 0; index < node.size(); index++) {
      JsonNode value = node.get(index);
      if (!value.isTextual() || value.asText().isBlank()) {
        errors.add(path + "[" + index + "] must be a non-blank string");
      }
    }
  }

  boolean isBlank(String value) {
    return value == null || value.isBlank();
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

  private String expandHome(String rawPath) {
    if (rawPath.equals("~")) {
      return System.getProperty("user.home");
    }
    return rawPath.startsWith("~/")
        ? System.getProperty("user.home") + rawPath.substring(1)
        : rawPath;
  }
}
