package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class PhaseDocument {

  @JsonProperty("name")
  public String name;

  @JsonProperty("description")
  public String description;

  @JsonProperty("dependsOn")
  public List<String> dependsOn;

  @JsonProperty("restartPolicy")
  public RestartPolicyDocument restartPolicy;

  @JsonProperty("continueOnModuleError")
  public boolean continueOnModuleError = true;

  @JsonProperty("modules")
  public List<ModuleDocument> modules;

  @JsonProperty("steps")
  public List<ModuleDocument> steps;
}
