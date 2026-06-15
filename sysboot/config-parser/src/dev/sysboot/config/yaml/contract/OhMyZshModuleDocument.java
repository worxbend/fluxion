package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class OhMyZshModuleDocument extends ModuleDocument {

  @JsonProperty("installDir")
  public String installDir;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
