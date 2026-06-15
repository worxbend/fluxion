package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ChecksumDocument {

  @JsonProperty("algorithm")
  public String algorithm;

  @JsonProperty("value")
  public String value;
}
