package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class DotbotModuleDocument extends ModuleDocument {

  @JsonProperty("installerVersion")
  public String installerVersion = "v0.2.1";

  @JsonProperty("config")
  public String config;

  @JsonProperty("dotbotBinary")
  public String dotbotBinary = "dotbot";

  @JsonProperty("probeCommand")
  public String probeCommand;
}
