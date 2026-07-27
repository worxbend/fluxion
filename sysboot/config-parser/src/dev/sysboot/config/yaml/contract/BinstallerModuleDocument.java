package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A {@code binstaller-profile} step: apply a binstaller {@code BinaryDistributionProfile}. */
public final class BinstallerModuleDocument extends ModuleDocument {

  /** Path to the BinaryDistributionProfile YAML. */
  @JsonProperty("config")
  @JsonAlias("configPath")
  public String config;

  /** Install only these tools. Empty means every tool in the profile. */
  @JsonProperty("only")
  public List<String> only;

  /** Omit these tools. */
  @JsonProperty("skip")
  public List<String> skip;

  /** Require a compatible lock file before applying. */
  @JsonProperty("locked")
  public boolean locked;

  @JsonProperty("lockFile")
  public String lockFile;

  @JsonProperty("installerVersion")
  public String installerVersion = dev.sysboot.core.KnownTools.BINSTALLER.version();

  @JsonProperty("binstallerBinary")
  public String binstallerBinary = dev.sysboot.core.KnownTools.BINSTALLER.executableName();

  @JsonProperty("probeCommand")
  public String probeCommand;

  @JsonProperty("continueOnError")
  public boolean continueOnError;
}
