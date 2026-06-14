package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class NerdFontModuleDto extends ModuleDto {

  @JsonProperty("installerVersion")
  public String installerVersion = "v1.0.5";

  @JsonProperty("nerdfontBinary")
  public String nerdfontBinary = "nerdfont-install";

  @JsonProperty("config")
  public NerdFontConfigDto config;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
