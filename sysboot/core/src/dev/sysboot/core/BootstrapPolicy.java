package dev.sysboot.core;

import java.util.Objects;
import java.util.Optional;

public record BootstrapPolicy(
    Optional<Boolean> dryRunDefault,
    Optional<Boolean> continueOnErrorDefault,
    Optional<Boolean> requireSudoDefault) {

  public BootstrapPolicy {
    dryRunDefault = copy(dryRunDefault, "dryRunDefault");
    continueOnErrorDefault = copy(continueOnErrorDefault, "continueOnErrorDefault");
    requireSudoDefault = copy(requireSudoDefault, "requireSudoDefault");
  }

  public static BootstrapPolicy empty() {
    return new BootstrapPolicy(Optional.empty(), Optional.empty(), Optional.empty());
  }

  private static Optional<Boolean> copy(Optional<Boolean> value, String fieldName) {
    return Objects.requireNonNull(value, fieldName);
  }
}
