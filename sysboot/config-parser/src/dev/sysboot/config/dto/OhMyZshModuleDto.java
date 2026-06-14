package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class OhMyZshModuleDto extends ModuleDto {

  @JsonProperty("installDir")
  public String installDir;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
