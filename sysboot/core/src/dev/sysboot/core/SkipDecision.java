package dev.sysboot.core;

import java.util.Objects;

public sealed interface SkipDecision permits SkipDecision.Skip, SkipDecision.Run {

  String itemKey();

  record Skip(String itemKey, InstallationStatus reason) implements SkipDecision {
    public Skip {
      Objects.requireNonNull(itemKey);
      Objects.requireNonNull(reason);
    }
  }

  record Run(String itemKey) implements SkipDecision {
    public Run {
      Objects.requireNonNull(itemKey);
    }
  }
}
