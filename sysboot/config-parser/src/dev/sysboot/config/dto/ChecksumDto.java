package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ChecksumDto {

  @JsonProperty("algorithm")
  public String algorithm;

  @JsonProperty("value")
  public String value;
}
