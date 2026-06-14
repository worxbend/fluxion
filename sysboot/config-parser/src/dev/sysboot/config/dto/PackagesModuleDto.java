package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class PackagesModuleDto extends ModuleDto {

  @JsonProperty("packageManager")
  public String packageManager;

  @JsonProperty("continueOnError")
  public boolean continueOnError = true;

  @JsonProperty("packages")
  public List<String> packages;
}
