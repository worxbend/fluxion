package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/** A {@code system-setting} step. */
public final class SystemSettingModuleDocument extends ModuleDocument {

  @JsonProperty("localRtc")
  public Boolean localRtc;

  @JsonProperty("ntp")
  public Boolean ntp;

  @JsonProperty("timezone")
  public String timezone;

  @JsonProperty("hostname")
  public String hostname;

  @JsonProperty("locale")
  public Map<String, String> locale;

  @JsonProperty("continueOnError")
  public boolean continueOnError;
}
