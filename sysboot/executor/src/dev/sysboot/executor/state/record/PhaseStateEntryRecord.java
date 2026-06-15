package dev.sysboot.executor.state.record;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class PhaseStateEntryRecord {

  @JsonProperty("phaseName")
  public String phaseName;

  @JsonProperty("status")
  public String status;

  @JsonProperty("completedAt")
  public String completedAt;

  public PhaseStateEntryRecord() {}

  public PhaseStateEntryRecord(String phaseName, String status, String completedAt) {
    this.phaseName = phaseName;
    this.status = status;
    this.completedAt = completedAt;
  }
}
