package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class CompiledBinaryModuleDto extends ModuleDto {

  @JsonProperty("binaryName")
  public String binaryName;

  @JsonProperty("url")
  public String url;

  @JsonProperty("checksum")
  public ChecksumDto checksum;

  @JsonProperty("installPath")
  public String installPath;

  @JsonProperty("continueOnError")
  public boolean continueOnError = false;

  @JsonProperty("versionCommand")
  public String versionCommand;

  @JsonProperty("expectedVersion")
  public String expectedVersion;
}
