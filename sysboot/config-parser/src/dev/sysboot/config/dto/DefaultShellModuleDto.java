package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class DefaultShellModuleDto extends ModuleDto {

  @JsonProperty("shell")
  public String shell;

  /** Deprecated alias kept for configs written before schemaVersion 1 was finalized. */
  @JsonProperty("shellPath")
  public String shellPath;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
