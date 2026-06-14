package dev.sysboot.executor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class PhaseStateEntryDto {

  @JsonProperty("phaseName")
  public String phaseName;

  @JsonProperty("status")
  public String status;

  @JsonProperty("completedAt")
  public String completedAt;

  public PhaseStateEntryDto() {}

  public PhaseStateEntryDto(String phaseName, String status, String completedAt) {
    this.phaseName = phaseName;
    this.status = status;
    this.completedAt = completedAt;
  }
}
