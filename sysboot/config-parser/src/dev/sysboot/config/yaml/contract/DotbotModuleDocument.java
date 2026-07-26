package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class DotbotModuleDocument extends ModuleDocument {

  @JsonProperty("installerVersion")
  public String installerVersion = dev.sysboot.core.KnownTools.DOTBOT_GO.version();

  @JsonProperty("config")
  public String config;

  @JsonProperty("dotbotBinary")
  public String dotbotBinary = "dotbot";

  @JsonProperty("probeCommand")
  public String probeCommand;
}
