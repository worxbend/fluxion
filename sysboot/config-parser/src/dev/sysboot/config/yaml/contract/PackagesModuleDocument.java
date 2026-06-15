package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class PackagesModuleDocument extends ModuleDocument {

  @JsonProperty("packageManager")
  public String packageManager;

  @JsonProperty("continueOnError")
  public boolean continueOnError = true;

  @JsonProperty("packages")
  public List<String> packages;
}
