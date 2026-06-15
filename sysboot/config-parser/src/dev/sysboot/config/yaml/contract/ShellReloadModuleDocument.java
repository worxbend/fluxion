package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class ShellReloadModuleDocument extends ModuleDocument {

  @JsonProperty("shell")
  public String shell = "zsh";

  @JsonProperty("description")
  public String description = "";
}
