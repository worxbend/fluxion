package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

public final class PackageSpecDocument {

  @JsonProperty("packageManager")
  private String packageManager;

  @JsonProperty("packages")
  private List<String> packages;

  @JsonProperty("apps")
  private List<String> apps;

  @JsonProperty("appIds")
  private List<String> appIds;

  @JsonProperty("remote")
  private String remote;

  public Optional<String> packageManager() {
    return DocumentDefaults.optional(packageManager);
  }

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
}
