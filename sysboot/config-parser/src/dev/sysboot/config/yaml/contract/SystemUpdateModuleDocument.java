package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A {@code system-update} step. */
public final class SystemUpdateModuleDocument extends ModuleDocument {

  @JsonProperty("packageManager")
  public String packageManager;

  /** Use the manager's full/dist upgrade. Required on rolling releases such as Tumbleweed. */
  @JsonProperty("distUpgrade")
  public boolean distUpgrade;

  @JsonProperty("refreshOnly")
  public boolean refreshOnly;

  @JsonProperty("timeout")
  public String timeout;

  @JsonProperty("continueOnError")
  public boolean continueOnError;
}
