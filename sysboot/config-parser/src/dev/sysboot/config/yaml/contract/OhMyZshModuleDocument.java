package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class OhMyZshModuleDocument extends ModuleDocument {

  @JsonProperty("installDir")
  public String installDir;

  @JsonProperty("revision")
  public String revision;

  @JsonProperty("sha256")
  public String sha256;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
