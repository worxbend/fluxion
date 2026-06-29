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

  @JsonProperty("signatureUrl")
  public String signatureUrl;

  @JsonProperty("installPath")
  public String installPath;

  @JsonProperty("archivePath")
  public String archivePath;

  @JsonProperty("stripComponents")
  public Integer stripComponents;

  @JsonProperty("mode")
  public String mode;

  @JsonProperty("installMode")
  public String installMode;

  @JsonProperty("symlink")
  public String symlink;

  @JsonProperty("symlinkPath")
  public String symlinkPath;

  @JsonProperty("continueOnError")
  public boolean continueOnError = false;

  @JsonProperty("versionCommand")
  public String versionCommand;

  @JsonProperty("expectedVersion")
  public String expectedVersion;
}
