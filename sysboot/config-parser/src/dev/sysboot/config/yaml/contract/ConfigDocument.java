package dev.sysboot.config.yaml.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ConfigDocument {

  @JsonProperty("schemaVersion")
  public Integer schemaVersion;

  @JsonProperty("profile")
  public String profile;

  @JsonProperty("os")
  public OsDocument os;

  /** Legacy flat list — still supported for backward compat. */
  @JsonProperty("modules")
  public List<ModuleDocument> modules;

  /** Primary workflow-style structure. */
  @JsonProperty("jobs")
  public List<PhaseDocument> jobs;

  /** Legacy alias for jobs. */
  @JsonProperty("phases")
  public List<PhaseDocument> phases;
}
