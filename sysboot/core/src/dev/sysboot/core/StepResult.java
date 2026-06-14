package dev.sysboot.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface StepResult
    permits StepResult.Success, StepResult.Failure, StepResult.Skipped, StepResult.DryRun {

  String item();

  record Success(String item, Duration elapsed, Optional<String> detectedVersion)
      implements StepResult {
    public Success {
      Objects.requireNonNull(item);
      Objects.requireNonNull(elapsed);
      detectedVersion = detectedVersion != null ? detectedVersion : Optional.empty();
    }

    public Success(String item, Duration elapsed) {
      this(item, elapsed, Optional.empty());
    }

    public Success(String item, Duration elapsed, String version) {
      this(item, elapsed, Optional.ofNullable(version));
    }
  }

  record Failure(String item, String errorMessage, int exitCode, Duration elapsed)
      implements StepResult {
    public Failure {
      Objects.requireNonNull(item);
      Objects.requireNonNull(errorMessage);
      Objects.requireNonNull(elapsed);
    }
  }

  record Skipped(String item, String reason) implements StepResult {
    public Skipped {
      Objects.requireNonNull(item);
      Objects.requireNonNull(reason);
    }
  }

  record DryRun(String item, List<String> wouldExecute) implements StepResult {
    public DryRun {
      Objects.requireNonNull(item);
      Objects.requireNonNull(wouldExecute);
      wouldExecute = List.copyOf(wouldExecute);
    }
  }
}
