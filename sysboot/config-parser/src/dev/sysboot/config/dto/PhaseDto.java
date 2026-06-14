package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class PhaseDto {

  @JsonProperty("name")
  public String name;

  @JsonProperty("description")
  public String description;

  @JsonProperty("dependsOn")
  public List<String> dependsOn;

  @JsonProperty("restartPolicy")
  public RestartPolicyDto restartPolicy;

  @JsonProperty("continueOnModuleError")
  public boolean continueOnModuleError = true;

  @JsonProperty("modules")
  public List<ModuleDto> modules;

  @JsonProperty("steps")
  public List<ModuleDto> steps;
}
