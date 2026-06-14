package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class DotbotModuleDto extends ModuleDto {

  @JsonProperty("installerVersion")
  public String installerVersion = "v0.2.1";

  @JsonProperty("config")
  public String config;

  @JsonProperty("dotbotBinary")
  public String dotbotBinary = "dotbot";

  @JsonProperty("probeCommand")
  public String probeCommand;
}
