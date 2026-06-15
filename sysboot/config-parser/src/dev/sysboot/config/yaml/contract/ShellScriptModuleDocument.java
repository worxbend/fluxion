package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ShellScriptModuleDocument extends ModuleDocument {

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
