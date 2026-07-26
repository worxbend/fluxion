package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class NerdFontModuleDocument extends ModuleDocument {

  @JsonProperty("installerVersion")
  public String installerVersion = dev.sysboot.core.KnownTools.NERD_FONTS_INSTALLER.version();

  @JsonProperty("nerdfontBinary")
  public String nerdfontBinary = dev.sysboot.core.KnownTools.NERD_FONTS_INSTALLER.executableName();

  @JsonProperty("config")
  public NerdFontConfigDocument config;

  /** Path to an existing {@code nerd-fonts-installer} config to use instead of a generated one. */
  @JsonProperty("configPath")
  public String configPath;

  @JsonProperty("probeCommand")
  public String probeCommand;
}
