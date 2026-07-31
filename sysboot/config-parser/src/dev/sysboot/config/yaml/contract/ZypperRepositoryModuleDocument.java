package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A {@code zypper-repository} step. */
public final class ZypperRepositoryModuleDocument extends ModuleDocument {

  @JsonProperty("id")
  public String id;

  @JsonProperty("baseUrl")
  public String baseUrl;

  @JsonProperty("repoFile")
  public String repoFile;

  @JsonProperty("gpgKeyUrl")
  public String gpgKeyUrl;

  @JsonProperty("enabled")
  public boolean enabled = true;

  @JsonProperty("gpgCheck")
  public boolean gpgCheck = true;

  @JsonProperty("autoRefresh")
  public boolean autoRefresh = true;

  @JsonProperty("checksum")
  public ChecksumDocument checksum;
}
