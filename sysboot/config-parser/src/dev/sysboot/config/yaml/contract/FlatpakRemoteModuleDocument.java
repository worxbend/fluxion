package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class FlatpakRemoteModuleDocument extends ModuleDocument {

  @JsonProperty("remote")
  public String remote;

  @JsonProperty("url")
  public String url;

  @JsonProperty("system")
  public Boolean system;
}
