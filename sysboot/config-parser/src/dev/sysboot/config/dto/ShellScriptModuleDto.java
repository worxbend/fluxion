package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ShellScriptModuleDto extends ModuleDto {

  @JsonProperty("script")
  public String script;

  @JsonProperty("args")
  public List<String> args;

  @JsonProperty("workingDir")
  public String workingDir;

  @JsonProperty("continueOnError")
  public boolean continueOnError = false;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
