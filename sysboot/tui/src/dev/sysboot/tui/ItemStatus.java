package dev.sysboot.tui;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record ItemStatus(
    String name,
    String module,
    ItemResult result,
    Optional<Duration> elapsed,
    Optional<String> detail) {

  public ItemStatus {
    Objects.requireNonNull(name);
    Objects.requireNonNull(module);
    Objects.requireNonNull(result);
    Objects.requireNonNull(elapsed);
    detail = detail == null ? Optional.empty() : detail;
  }

  public ItemStatus(String name, String module, ItemResult result, Optional<Duration> elapsed) {
    this(name, module, result, elapsed, Optional.empty());
  }

  public static ItemStatus pending(String name, String module) {
    return new ItemStatus(name, module, ItemResult.PENDING, Optional.empty());
  }

  public static ItemStatus running(String name, String module) {
    return new ItemStatus(name, module, ItemResult.RUNNING, Optional.empty());
  }

  public static ItemStatus skipped(String name, String module, String reason) {
    return new ItemStatus(name, module, ItemResult.SKIPPED, Optional.empty(), Optional.of(reason));
  }

  public ItemStatus withResult(ItemResult newResult, Duration duration) {
    return new ItemStatus(name, module, newResult, Optional.of(duration), detail);
  }

  public ItemStatus withDetail(Optional<String> newDetail) {
    return new ItemStatus(name, module, result, elapsed, newDetail);
  }
}
