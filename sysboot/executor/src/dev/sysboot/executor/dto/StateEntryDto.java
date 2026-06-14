package dev.sysboot.executor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class StateEntryDto {

  @JsonProperty("profileName")
  public String profileName;

  @JsonProperty("moduleName")
  public String moduleName;

  @JsonProperty("itemKey")
  public String itemKey;

  @JsonProperty("itemType")
  public String itemType;

  @JsonProperty("completedAt")
  public String completedAt;

  @JsonProperty("version")
  public String version;

  @JsonProperty("checksum")
  public String checksum;

  public StateEntryDto() {}

  public StateEntryDto(
      String profileName,
      String moduleName,
      String itemKey,
      String itemType,
      String completedAt,
      String version,
      String checksum) {
    this.profileName = profileName;
    this.moduleName = moduleName;
    this.itemKey = itemKey;
    this.itemType = itemType;
    this.completedAt = completedAt;
    this.version = version;
    this.checksum = checksum;
  }
}
