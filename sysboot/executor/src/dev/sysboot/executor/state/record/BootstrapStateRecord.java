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

  @JsonProperty("planEntryEntries")
  public List<PlanEntryStateEntryRecord> planEntryEntries;

  @JsonProperty("nextPlanEntry")
  public String nextPlanEntry;

  @JsonProperty("manifestIdentity")
  public String manifestIdentity;

  @JsonProperty("manifestFingerprint")
  public String manifestFingerprint;

  public BootstrapStateRecord() {}

  public BootstrapStateRecord(
      int schemaVersion,
      String profileName,
      List<StateEntryRecord> entries,
      List<PhaseStateEntryRecord> phaseEntries) {
    this(schemaVersion, profileName, entries, phaseEntries, List.of(), null);
  }

  public BootstrapStateRecord(
      int schemaVersion,
      String profileName,
      List<StateEntryRecord> entries,
      List<PhaseStateEntryRecord> phaseEntries,
      List<PlanEntryStateEntryRecord> planEntryEntries,
      String nextPlanEntry) {
    this(
        schemaVersion,
        profileName,
        entries,
        phaseEntries,
        planEntryEntries,
        nextPlanEntry,
        null,
        null);
  }

  public BootstrapStateRecord(
      int schemaVersion,
      String profileName,
      List<StateEntryRecord> entries,
      List<PhaseStateEntryRecord> phaseEntries,
      List<PlanEntryStateEntryRecord> planEntryEntries,
      String nextPlanEntry,
      String manifestIdentity,
      String manifestFingerprint) {
    this.schemaVersion = schemaVersion;
    this.profileName = profileName;
    this.entries = entries;
    this.phaseEntries = phaseEntries;
    this.planEntryEntries = planEntryEntries;
    this.nextPlanEntry = nextPlanEntry;
    this.manifestIdentity = manifestIdentity;
    this.manifestFingerprint = manifestFingerprint;
  }
}
