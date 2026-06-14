package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ShellCommandModuleDto extends ModuleDto {

  @JsonProperty("commands")
  public List<String> commands;

  @JsonProperty("shell")
  public String shell = "/bin/bash";

  @JsonProperty("workingDir")
  public String workingDir;

  @JsonProperty("continueOnError")
  public boolean continueOnError = false;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
