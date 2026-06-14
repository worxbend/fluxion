package dev.sysboot.executor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class BootstrapStateDto {

  @JsonProperty("schemaVersion")
  public int schemaVersion;

  @JsonProperty("profileName")
  public String profileName;

  @JsonProperty("entries")
  public List<StateEntryDto> entries;

  @JsonProperty("phaseEntries")
  public List<PhaseStateEntryDto> phaseEntries;

  public BootstrapStateDto() {}

  public BootstrapStateDto(
      int schemaVersion,
      String profileName,
      List<StateEntryDto> entries,
      List<PhaseStateEntryDto> phaseEntries) {
    this.schemaVersion = schemaVersion;
    this.profileName = profileName;
    this.entries = entries;
    this.phaseEntries = phaseEntries;
  }
}
