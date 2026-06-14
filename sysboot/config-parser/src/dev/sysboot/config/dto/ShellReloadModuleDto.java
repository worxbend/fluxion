package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ShellReloadModuleDto extends ModuleDto {

  @JsonProperty("shell")
  public String shell = "zsh";

  @JsonProperty("description")
  public String description = "";
}
