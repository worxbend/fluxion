package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

public final class PlanSpecDocument {

  @JsonProperty("packages")
  private List<String> packages;

  @JsonProperty("apps")
  private List<String> apps;

  @JsonProperty("appIds")
  private List<String> appIds;

  @JsonProperty("remote")
  private String remote;

  @JsonProperty("source")
  private SourceSpecDocument source;

  @JsonProperty("sources")
  private List<SourceDocument> sources;

  @JsonProperty("checksum")
  private WorkstationChecksumDocument checksum;

  @JsonProperty("binaryName")
  private String binaryName;

  @JsonProperty("url")
  private String url;

  @JsonProperty("installPath")
  private String installPath;

  @JsonProperty("commands")
  private List<String> commands;

  @JsonProperty("args")
  private List<String> args;

  public List<String> packages() {
    return DocumentDefaults.list(packages);
  }

  public List<String> apps() {
    return DocumentDefaults.list(apps);
  }

  public List<String> appIds() {
    return DocumentDefaults.list(appIds);
  }

  public Optional<String> remote() {
    return DocumentDefaults.optional(remote);
  }

  public Optional<SourceSpecDocument> source() {
    return DocumentDefaults.optional(source);
  }

  public List<SourceDocument> sources() {
    return DocumentDefaults.list(sources);
  }

  public Optional<WorkstationChecksumDocument> checksum() {
    return DocumentDefaults.optional(checksum);
  }

  public Optional<String> binaryName() {
    return DocumentDefaults.optional(binaryName);
  }

  public Optional<String> url() {
    return DocumentDefaults.optional(url);
  }

  public Optional<String> installPath() {
    return DocumentDefaults.optional(installPath);
  }

  public List<String> commands() {
    return DocumentDefaults.list(commands);
  }

  public List<String> args() {
    return DocumentDefaults.list(args);
  }
}
