package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public final class WorkstationChecksumDocument {

  @JsonProperty("algorithm")
  private String algorithm;

  @JsonProperty("value")
  private String value;

  public Optional<String> algorithm() {
    return DocumentDefaults.optional(algorithm);
  }

  public Optional<String> value() {
    return DocumentDefaults.optional(value);
  }
}
