package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A {@code systemd-unit} step. */
public final class SystemdUnitModuleDocument extends ModuleDocument {

  /** system | user */
  @JsonProperty("scope")
  public String scope = "system";

  @JsonProperty("units")
  public List<UnitDocument> units;

  @JsonProperty("continueOnError")
  public boolean continueOnError;

  /** One unit entry. */
  public static final class UnitDocument {

    @JsonProperty("name")
    public String name;

    @JsonProperty("enabled")
    public boolean enabled = true;

    /** started | stopped | unchanged */
    @JsonProperty("state")
    public String state = "unchanged";

    @JsonProperty("mask")
    public boolean mask;
  }
}
