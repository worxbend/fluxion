package dev.sysboot.tui;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record ItemStatus(
    String name, String module, ItemResult result, Optional<Duration> elapsed) {

  public ItemStatus {
    Objects.requireNonNull(name);
    Objects.requireNonNull(module);
    Objects.requireNonNull(result);
    Objects.requireNonNull(elapsed);
  }

  public static ItemStatus pending(String name, String module) {
    return new ItemStatus(name, module, ItemResult.PENDING, Optional.empty());
  }

  public static ItemStatus running(String name, String module) {
    return new ItemStatus(name, module, ItemResult.RUNNING, Optional.empty());
  }

  public ItemStatus withResult(ItemResult newResult, Duration duration) {
    return new ItemStatus(name, module, newResult, Optional.of(duration));
  }
}
