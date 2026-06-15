package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class OsDocument {

  @JsonProperty("type")
  public String type;

  @JsonProperty("release")
  public String release;
}
