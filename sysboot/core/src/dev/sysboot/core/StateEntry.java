package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;

public record StateEntry(
    String profileName,
    String moduleName,
    String itemKey,
    ItemType itemType,
    Instant completedAt,
    String version,
    String checksum) {

  public StateEntry {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(moduleName);
    Objects.requireNonNull(itemKey);
    Objects.requireNonNull(itemType);
    Objects.requireNonNull(completedAt);
  }
}
