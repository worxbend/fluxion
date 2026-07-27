package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

public final class PlanEntryDocument {

  @JsonProperty("name")
  private String name;

  @JsonProperty("kind")
  private String kind;

  @JsonProperty("description")
  private String description;

  @JsonProperty("dependsOn")
  private List<String> dependsOn;

  @JsonProperty("when")
  private WhenDocument when;

  @JsonProperty("execution")
  private ExecutionDocument execution;

  @JsonProperty("spec")
  private PlanSpecDocument spec;

  public Optional<String> name() {
    return DocumentDefaults.optional(name);
  }

  public Optional<String> kind() {
    return DocumentDefaults.optional(kind);
  }

  public Optional<String> description() {
    return DocumentDefaults.optional(description);
  }

  public List<String> dependsOn() {
    return DocumentDefaults.list(dependsOn);
  }

  public Optional<WhenDocument> when() {
    return DocumentDefaults.optional(when);
  }

  public Optional<ExecutionDocument> execution() {
    return DocumentDefaults.optional(execution);
  }

  public Optional<PlanSpecDocument> spec() {
    return DocumentDefaults.optional(spec);
  }
}
