package dev.sysboot.config.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class ConfigDto {

  @JsonProperty("schemaVersion")
  public Integer schemaVersion;

  @JsonProperty("profile")
  public String profile;

  @JsonProperty("os")
  public OsDto os;

  /** Legacy flat list — still supported for backward compat. */
  @JsonProperty("modules")
  public List<ModuleDto> modules;

  /** Primary workflow-style structure. */
  @JsonProperty("jobs")
  public List<PhaseDto> jobs;

  /** Legacy alias for jobs. */
  @JsonProperty("phases")
  public List<PhaseDto> phases;
}
