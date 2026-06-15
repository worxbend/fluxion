package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ShellCommandModuleDocument extends ModuleDocument {

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
