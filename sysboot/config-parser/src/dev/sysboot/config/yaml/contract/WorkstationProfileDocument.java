package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public final class WorkstationProfileDocument {

  @JsonProperty("apiVersion")
  private String apiVersion;

  @JsonProperty("kind")
  private String kind;

  @JsonProperty("metadata")
  private MetadataDocument metadata;

  @JsonProperty("spec")
  private SpecDocument spec;

  public Optional<String> apiVersion() {
    return DocumentDefaults.optional(apiVersion);
  }

  public Optional<String> kind() {
    return DocumentDefaults.optional(kind);
  }

  public Optional<MetadataDocument> metadata() {
    return DocumentDefaults.optional(metadata);
  }

  public Optional<SpecDocument> spec() {
    return DocumentDefaults.optional(spec);
  }
}
