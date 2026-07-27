package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** A {@code git-config} step. */
public final class GitConfigModuleDocument extends ModuleDocument {

  /** global | system | local */
  @JsonProperty("scope")
  public String scope = "global";

  @JsonProperty("entries")
  public Map<String, String> entries;

  @JsonProperty("continueOnError")
  public boolean continueOnError;
}
