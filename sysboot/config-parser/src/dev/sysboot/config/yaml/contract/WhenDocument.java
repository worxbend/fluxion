package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WhenDocument {

  @JsonProperty("os")
  private List<String> os;

  @JsonProperty("distributions")
  private List<String> distributions;

  @JsonProperty("architectures")
  private List<String> architectures;

  @JsonProperty("commands")
  private List<String> commands;

  @JsonProperty("files")
  private List<String> files;

  @JsonProperty("vars")
  private Map<String, String> vars;

  @JsonProperty("expression")
  private String expression;

  public List<String> os() {
    return DocumentDefaults.list(os);
  }

  public List<String> distributions() {
    return DocumentDefaults.list(distributions);
  }

  public List<String> architectures() {
    return DocumentDefaults.list(architectures);
  }

  public List<String> commands() {
    return DocumentDefaults.list(commands);
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
}
