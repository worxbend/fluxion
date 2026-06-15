package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class AptRepositoryModuleDocument extends ModuleDocument {

  @JsonProperty("source")
  public String source;

  @JsonProperty("sourceList")
  public String sourceList;

  @JsonProperty("signingKeyUrl")
  public String signingKeyUrl;

  @JsonProperty("keyring")
  public String keyring;
}
