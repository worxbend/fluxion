package dev.sysboot.core;

import java.time.Duration;
import java.util.Objects;

public record ProcessResult(int exitCode, String stdout, String stderr, Duration elapsed) {

  public ProcessResult {
    Objects.requireNonNull(stdout);
    Objects.requireNonNull(stderr);
    Objects.requireNonNull(elapsed);
  }

  public boolean isSuccess() {
    return exitCode == 0;
  }
}
