package dev.sysboot.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplyCommandPrivilegeOrderingTest {

  @TempDir Path tempDir;

  @Test
  void preflightBeforeReset_whenPreflightFails_preservesStateFile() throws Exception {
    Path stateFile = Files.writeString(tempDir.resolve("state.json"), "existing-state");

    assertThatThrownBy(
            () ->
                ApplyCommand.preflightBeforeReset(
                    false,
                    false,
                    true,
                    () -> {
                      throw new IllegalStateException("sudo failed");
                    },
                    () -> delete(stateFile)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("sudo failed");
    assertThat(stateFile).hasContent("existing-state");
  }

  private void delete(Path path) {
    try {
      Files.delete(path);
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
