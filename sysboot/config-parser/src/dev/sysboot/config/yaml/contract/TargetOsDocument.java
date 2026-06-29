package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public final class TargetOsDocument {

  @JsonProperty("family")
  private String family;

  @JsonProperty("distribution")
  private String distribution;

  @JsonProperty("release")
  private String release;

  @JsonProperty("version")
  private String version;

  @JsonProperty("codename")
  private String codename;

  public Optional<String> family() {
    return DocumentDefaults.optional(family);
  }

  public Optional<String> distribution() {
    return DocumentDefaults.optional(distribution);
  }

  public Optional<String> release() {
    return DocumentDefaults.optional(release);
  }

  public Optional<String> version() {
    return DocumentDefaults.optional(version);
  }

  public Optional<String> codename() {
    return DocumentDefaults.optional(codename);
  }
}
