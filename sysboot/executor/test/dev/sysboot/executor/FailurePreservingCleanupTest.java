package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class FailurePreservingCleanupTest {

  @Test
  void run_whenPrimaryFailureExists_suppressesCleanupFailure() throws Exception {
    var primary = new IOException("digest mismatch");
    var cleanup = new IOException("delete failed");

    FailurePreservingCleanup.run(
        primary,
        () -> {
          throw cleanup;
        });

    assertThat(primary.getSuppressed()).containsExactly(cleanup);
  }

  @Test
  void run_whenNoPrimaryFailure_propagatesCleanupFailure() {
    assertThatThrownBy(
            () ->
                FailurePreservingCleanup.run(
                    null,
                    () -> {
                      throw new IOException("close failed");
                    }))
        .isInstanceOf(IOException.class)
        .hasMessage("close failed");
  }
}
