package dev.sysboot.executor;

import java.io.IOException;

final class FailurePreservingCleanup {

  private FailurePreservingCleanup() {}

  static void run(Throwable primaryFailure, Action action) throws IOException {
    try {
      action.run();
    } catch (IOException | RuntimeException cleanupFailure) {
      if (primaryFailure != null) {
        primaryFailure.addSuppressed(cleanupFailure);
        return;
      }
      throw cleanupFailure;
    }
  }

  @FunctionalInterface
  interface Action {
    void run() throws IOException;
  }
}
