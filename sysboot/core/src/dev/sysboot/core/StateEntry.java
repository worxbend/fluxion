package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record StateEntry(
    String profileName,
    String moduleName,
    String itemKey,
    ItemType itemType,
    Instant completedAt,
    Optional<String> version,
    Optional<String> checksum) {

  public StateEntry {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(moduleName);
    Objects.requireNonNull(itemKey);
    Objects.requireNonNull(itemType);
    Objects.requireNonNull(completedAt);
    Objects.requireNonNull(version);
    Objects.requireNonNull(checksum);
  }
}
