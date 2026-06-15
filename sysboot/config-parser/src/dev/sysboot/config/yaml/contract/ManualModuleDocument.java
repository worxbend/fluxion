package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ManualModuleDocument extends ModuleDocument {

  @JsonProperty("message")
  public String message;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
