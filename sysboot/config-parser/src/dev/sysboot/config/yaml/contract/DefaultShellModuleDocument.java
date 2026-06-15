package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class DefaultShellModuleDocument extends ModuleDocument {

  @JsonProperty("shell")
  public String shell;

  /** Deprecated alias kept for configs written before schemaVersion 1 was finalized. */
  @JsonProperty("shellPath")
  public String shellPath;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
