package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ToolchainModuleDto extends ModuleDto {

  @JsonProperty("kind")
  public String kind;

  @JsonProperty("installScriptUrl")
  public String installScriptUrl;

  /** Deprecated alias kept for configs written before schemaVersion 1 was finalized. */
  @JsonProperty("installScript")
  public String installScript;

  @JsonProperty("installArgs")
  public List<String> installArgs;

  @JsonProperty("postInstallEnvSource")
  public String postInstallEnvSource;

  @JsonProperty("probeCommand")
  public String probeCommand;

  @JsonProperty("continueOnError")
  public boolean continueOnError = true;
}
