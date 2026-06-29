package dev.sysboot.executor.state.record;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class PlanEntryStateEntryRecord {

  @JsonProperty("entryName")
  public String entryName;

  @JsonProperty("status")
  public String status;

  @JsonProperty("updatedAt")
  public String updatedAt;

  @JsonProperty("message")
  public String message;

  public PlanEntryStateEntryRecord() {}

  public PlanEntryStateEntryRecord(
      String entryName, String status, String updatedAt, String message) {
    this.entryName = entryName;
    this.status = status;
    this.updatedAt = updatedAt;
    this.message = message;
  }
}
