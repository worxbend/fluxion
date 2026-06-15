package dev.sysboot.executor.state.record;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class PhaseStateEntryRecord {

  @JsonProperty("phaseName")
  public String phaseName;

  @JsonProperty("status")
  public String status;

  @JsonProperty("completedAt")
  public String completedAt;

  @JsonProperty("fingerprint")
  public String fingerprint;

  public PhaseStateEntryRecord() {}

  public PhaseStateEntryRecord(String phaseName, String status, String completedAt) {
    this(phaseName, status, completedAt, null);
  }

  public PhaseStateEntryRecord(
      String phaseName, String status, String completedAt, String fingerprint) {
    this.phaseName = phaseName;
    this.status = status;
    this.completedAt = completedAt;
    this.fingerprint = fingerprint;
  }
}
