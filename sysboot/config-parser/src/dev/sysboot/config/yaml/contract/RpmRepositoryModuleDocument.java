package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class RpmRepositoryModuleDocument extends ModuleDocument {

  @JsonProperty("id")
  public String id;

  @JsonProperty("baseUrl")
  public String baseUrl;

  @JsonProperty("repoFile")
  public String repoFile;

  @JsonProperty("gpgKeyUrl")
  public String gpgKeyUrl;

  @JsonProperty("enabled")
  public Boolean enabled;

  @JsonProperty("gpgCheck")
  public Boolean gpgCheck;
}
