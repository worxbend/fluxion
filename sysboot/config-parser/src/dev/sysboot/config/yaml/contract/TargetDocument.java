package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public final class TargetDocument {

  @JsonProperty("os")
  private TargetOsDocument os;

  @JsonProperty("architecture")
  private String architecture;

  public Optional<TargetOsDocument> os() {
    return DocumentDefaults.optional(os);
  }

  public Optional<String> architecture() {
    return DocumentDefaults.optional(architecture);
  }
}
