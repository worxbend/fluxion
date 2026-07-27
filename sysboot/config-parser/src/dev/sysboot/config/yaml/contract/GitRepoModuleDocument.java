package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** A {@code git-repo} step. */
public final class GitRepoModuleDocument extends ModuleDocument {

  @JsonProperty("repos")
  public List<RepoDocument> repos;

  @JsonProperty("continueOnError")
  public boolean continueOnError;

  /** One repository entry. */
  public static final class RepoDocument {

    @JsonProperty("url")
    public String url;

    @JsonProperty("dest")
    public String dest;

    @JsonProperty("ref")
    public String ref;

    @JsonProperty("depth")
    public Integer depth;

    @JsonProperty("submodules")
    public boolean submodules;

    /** none | pull | reset-hard */
    @JsonProperty("update")
    public String update = "none";
  }
}
