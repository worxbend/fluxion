package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class AssertModuleDocument extends ModuleDocument {

  @JsonProperty("command")
  public String command;

  @JsonProperty("message")
  public String message;

  @JsonProperty("shell")
  public String shell = "/bin/bash";

  @JsonProperty("workingDir")
  public String workingDir;
}
