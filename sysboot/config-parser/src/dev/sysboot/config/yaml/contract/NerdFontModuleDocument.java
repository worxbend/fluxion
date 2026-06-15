package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class NerdFontModuleDocument extends ModuleDocument {

  @JsonProperty("installerVersion")
  public String installerVersion = "v1.0.5";

  @JsonProperty("nerdfontBinary")
  public String nerdfontBinary = "nerdfont-install";

  @JsonProperty("config")
  public NerdFontConfigDocument config;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
