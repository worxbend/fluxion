package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Optional;

public final class MetadataDocument {

  @JsonProperty("name")
  private String name;

  @JsonProperty("namespace")
  private String namespace;

  @JsonProperty("labels")
  private Map<String, String> labels;

  @JsonProperty("annotations")
  private Map<String, String> annotations;

  public Optional<String> name() {
    return DocumentDefaults.optional(name);
  }

  public Optional<String> namespace() {
    return DocumentDefaults.optional(namespace);
  }

  public Map<String, String> labels() {
    return DocumentDefaults.map(labels);
  }

  public Map<String, String> annotations() {
    return DocumentDefaults.map(annotations);
  }
}
