package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class CompiledBinaryModuleDocument extends ModuleDocument {

  @JsonProperty("binaryName")
  public String binaryName;

  @JsonProperty("url")
  public String url;

  @JsonProperty("checksum")
  public ChecksumDocument checksum;

  @JsonProperty("checksumUrl")
  public String checksumUrl;

  @JsonProperty("installPath")
  public String installPath;

  @JsonProperty("continueOnError")
  public boolean continueOnError = false;

  @JsonProperty("versionCommand")
  public String versionCommand;

  @JsonProperty("expectedVersion")
  public String expectedVersion;
}
