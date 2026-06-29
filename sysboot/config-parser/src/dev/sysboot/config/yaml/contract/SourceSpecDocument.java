package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Optional;

public final class SourceSpecDocument {

  @JsonProperty("source")
  private String source;

  @JsonProperty("sourceList")
  private String sourceList;

  @JsonProperty("signingKeyUrl")
  private String signingKeyUrl;

  @JsonProperty("keyring")
  private String keyring;

  @JsonProperty("id")
  private String id;

  @JsonProperty("baseUrl")
  private String baseUrl;

  @JsonProperty("repoFile")
  private String repoFile;

  @JsonProperty("gpgKeyUrl")
  private String gpgKeyUrl;

  @JsonProperty("repository")
  private String repository;

  @JsonProperty("server")
  private String server;

  @JsonProperty("config")
  private String config;

  @JsonProperty("sigLevel")
  private String sigLevel;

  @JsonProperty("include")
  private String include;

  @JsonProperty("remote")
  private String remote;

  @JsonProperty("url")
  private String url;

  @JsonProperty("enabled")
  private Boolean enabled;

  @JsonProperty("gpgCheck")
  private Boolean gpgCheck;

  @JsonProperty("checksum")
  private WorkstationChecksumDocument checksum;

  public Optional<String> source() {
    return DocumentDefaults.optional(source);
  }

  public Optional<String> sourceList() {
    return DocumentDefaults.optional(sourceList);
  }

  public Optional<String> signingKeyUrl() {
    return DocumentDefaults.optional(signingKeyUrl);
  }

  public Optional<String> keyring() {
    return DocumentDefaults.optional(keyring);
  }

  public Optional<String> id() {
    return DocumentDefaults.optional(id);
  }

  public Optional<String> baseUrl() {
    return DocumentDefaults.optional(baseUrl);
  }

  public Optional<String> repoFile() {
    return DocumentDefaults.optional(repoFile);
  }

  public Optional<String> gpgKeyUrl() {
    return DocumentDefaults.optional(gpgKeyUrl);
  }

  public Optional<String> repository() {
    return DocumentDefaults.optional(repository);
  }

  public Optional<String> server() {
    return DocumentDefaults.optional(server);
  }

  public Optional<String> config() {
    return DocumentDefaults.optional(config);
  }

  public Optional<String> sigLevel() {
    return DocumentDefaults.optional(sigLevel);
  }

  public Optional<String> include() {
    return DocumentDefaults.optional(include);
  }

  public Optional<String> remote() {
    return DocumentDefaults.optional(remote);
  }

  public Optional<String> url() {
    return DocumentDefaults.optional(url);
  }

  public Optional<Boolean> enabled() {
    return DocumentDefaults.optional(enabled);
  }

  public Optional<Boolean> gpgCheck() {
    return DocumentDefaults.optional(gpgCheck);
  }

  public Optional<WorkstationChecksumDocument> checksum() {
    return DocumentDefaults.optional(checksum);
  }
}
