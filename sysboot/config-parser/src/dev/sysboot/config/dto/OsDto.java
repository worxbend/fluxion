package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class OsDto {

  @JsonProperty("type")
  public String type;

  @JsonProperty("release")
  public String release;
}
