package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class PackageActionDocument {

  private final String action;
  private final List<String> args;

  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public PackageActionDocument(JsonNode node) {
    if (node != null && node.isTextual()) {
      this.action = node.asText();
      this.args = List.of();
    } else if (node != null && node.isObject()) {
      this.action = textField(node, "action");
      this.args = argsField(node);
    } else {
      this.action = null;
      this.args = List.of();
    }
  }

  public Optional<String> action() {
    return DocumentDefaults.optional(action);
  }

  public List<String> args() {
    return args;
  }

  private String textField(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    return value.isTextual() ? value.asText() : "";
  }

  private List<String> argsField(JsonNode node) {
    JsonNode value = node.get("args");
    if (value == null || value.isNull()) {
      return List.of();
    }
    if (!value.isArray()) {
      return List.of("");
    }
    var parsed = new ArrayList<String>();
    value.forEach(item -> parsed.add(item.isTextual() ? item.asText() : ""));
    return List.copyOf(parsed);
  }
}
