package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WhenDocument {

  @JsonProperty("os")
  private JsonNode os;

  @JsonProperty("osFamily")
  private JsonNode osFamily;

  @JsonProperty("distribution")
  private JsonNode distribution;

  @JsonProperty("distributions")
  private JsonNode distributions;

  @JsonProperty("version")
  private JsonNode version;

  @JsonProperty("codename")
  private JsonNode codename;

  @JsonProperty("architecture")
  private JsonNode architecture;

  @JsonProperty("architectures")
  private JsonNode architectures;

  @JsonProperty("commands")
  private JsonNode commands;

  @JsonProperty("commandExists")
  private JsonNode commandExists;

  @JsonProperty("oneOf")
  private List<WhenDocument> oneOf;

  @JsonProperty("files")
  private List<String> files;

  @JsonProperty("vars")
  private Map<String, String> vars;

  @JsonProperty("expression")
  private String expression;

  public List<String> os() {
    return stringList(os);
  }

  public Optional<JsonNode> osCondition() {
    return DocumentDefaults.optional(os);
  }

  public Optional<JsonNode> osFamilyCondition() {
    return DocumentDefaults.optional(osFamily);
  }

  public Optional<JsonNode> distributionCondition() {
    return DocumentDefaults.optional(distribution);
  }

  public List<String> distributions() {
    return stringList(distributions);
  }

  public Optional<JsonNode> distributionsCondition() {
    return DocumentDefaults.optional(distributions);
  }

  public Optional<JsonNode> versionCondition() {
    return DocumentDefaults.optional(version);
  }

  public Optional<JsonNode> codenameCondition() {
    return DocumentDefaults.optional(codename);
  }

  public Optional<JsonNode> architectureCondition() {
    return DocumentDefaults.optional(architecture);
  }

  public List<String> architectures() {
    return stringList(architectures);
  }

  public Optional<JsonNode> architecturesCondition() {
    return DocumentDefaults.optional(architectures);
  }

  public List<String> commands() {
    return stringList(commands);
  }

  public Optional<JsonNode> commandsCondition() {
    return DocumentDefaults.optional(commands);
  }

  public Optional<JsonNode> commandExistsCondition() {
    return DocumentDefaults.optional(commandExists);
  }

  public List<WhenDocument> oneOf() {
    return DocumentDefaults.list(oneOf);
  }

  public List<String> files() {
    return DocumentDefaults.list(files);
  }

  public Map<String, String> vars() {
    return DocumentDefaults.map(vars);
  }

  public Optional<String> expression() {
    return DocumentDefaults.optional(expression);
  }

  private List<String> stringList(JsonNode node) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      return List.of();
    }
    if (node.isTextual()) {
      return List.of(node.asText());
    }
    if (!node.isArray()) {
      return List.of();
    }
    var values = new ArrayList<String>();
    node.forEach(value -> {
      if (value.isTextual()) {
        values.add(value.asText());
      }
    });
    return List.copyOf(values);
  }
}
