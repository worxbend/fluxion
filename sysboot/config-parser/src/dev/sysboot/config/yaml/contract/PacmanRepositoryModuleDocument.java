package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class PacmanRepositoryModuleDocument extends ModuleDocument {

  @JsonProperty("repository")
  public String repository;

  @JsonProperty("server")
  public String server;

  @JsonProperty("config")
  public String config;

  @JsonProperty("sigLevel")
  public String sigLevel;

  @JsonProperty("include")
  public String include;

  @JsonProperty("enabled")
  public Boolean enabled;
}
