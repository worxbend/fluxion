package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public final class SourceDocument {

  @JsonProperty("name")
  private String name;

  @JsonProperty("kind")
  private String kind;

  @JsonProperty("spec")
  private SourceSpecDocument spec;

  public Optional<String> name() {
    return DocumentDefaults.optional(name);
  }

  public Optional<String> kind() {
    return DocumentDefaults.optional(kind);
  }

  public Optional<SourceSpecDocument> spec() {
    return DocumentDefaults.optional(spec);
  }
}
