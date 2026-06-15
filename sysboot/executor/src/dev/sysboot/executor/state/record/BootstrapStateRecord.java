package dev.sysboot.executor.state.record;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public final class BootstrapStateRecord {

  @JsonProperty("schemaVersion")
  public int schemaVersion;

  @JsonProperty("profileName")
  public String profileName;

  @JsonProperty("entries")
  public List<StateEntryRecord> entries;

  @JsonProperty("phaseEntries")
  public List<PhaseStateEntryRecord> phaseEntries;

  public BootstrapStateRecord() {}

  public BootstrapStateRecord(
      int schemaVersion,
      String profileName,
      List<StateEntryRecord> entries,
      List<PhaseStateEntryRecord> phaseEntries) {
    this.schemaVersion = schemaVersion;
    this.profileName = profileName;
    this.entries = entries;
    this.phaseEntries = phaseEntries;
  }
}
