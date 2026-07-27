package dev.sysboot.core;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public sealed interface StepResult
    permits StepResult.Success,
        StepResult.Failure,
        StepResult.Skipped,
        StepResult.DryRun,
        StepResult.Paused {

  String item();

  record Success(
      String item, Duration elapsed, Optional<String> detectedVersion, Optional<String> checksum)
      implements StepResult {
    public Success {
      Objects.requireNonNull(item);
      Objects.requireNonNull(elapsed);
      detectedVersion = detectedVersion != null ? detectedVersion : Optional.empty();
      checksum = checksum != null ? checksum : Optional.empty();
    }

    public Success(String item, Duration elapsed) {
      this(item, elapsed, Optional.empty(), Optional.empty());
    }

    public Success(String item, Duration elapsed, String version) {
      this(item, elapsed, Optional.ofNullable(version), Optional.empty());
    }

    public Success(
        String item, Duration elapsed, Optional<String> detectedVersion, String checksum) {
      this(item, elapsed, detectedVersion, Optional.ofNullable(checksum));
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

  record Paused(String item, String message, Optional<String> nextPlanEntry, int exitCode)
      implements StepResult {
    public Paused {
      Objects.requireNonNull(item);
      Objects.requireNonNull(message);
      nextPlanEntry = nextPlanEntry == null ? Optional.empty() : nextPlanEntry;
    }
  }
}
