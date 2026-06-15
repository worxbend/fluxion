package dev.sysboot.executor;

import java.util.List;
import java.util.Objects;

public record StatusReport(String profileName, List<StatusReport.Item> items, Summary summary) {

  public StatusReport {
    Objects.requireNonNull(profileName);
    items = List.copyOf(Objects.requireNonNull(items));
    Objects.requireNonNull(summary);
  }

  public record Item(
      String key,
      String displayName,
      String type,
      Classification classification,
      String detail,
      String stateVersion,
      String liveVersion) {

    public Item {
      Objects.requireNonNull(key);
      Objects.requireNonNull(displayName);
      Objects.requireNonNull(type);
      Objects.requireNonNull(classification);
    }
  }

  public record Summary(
      int total,
      int configuredInstalled,
      int configuredMissing,
      int stateOnly,
      int unknown,
      int versionDrift) {}

  public enum Classification {
    CONFIGURED_INSTALLED,
    CONFIGURED_MISSING,
    STATE_ONLY,
    UNKNOWN,
    VERSION_DRIFT
  }
}
