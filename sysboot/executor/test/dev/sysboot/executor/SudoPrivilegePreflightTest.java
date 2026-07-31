package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SudoPrivilegePreflightTest {

  @Test
  void nonInteractive_whenSudoTimestampIsValid_succeedsWithoutPrompt() {
    var runner = new RecordingRunner(0);

    SudoPrivilegePreflight.nonInteractive(runner).verify();

    org.assertj.core.api.Assertions.assertThat(runner.command)
        .containsExactly(SudoCommand.executable(), "-n", "-v");
  }

  @Test
  void nonInteractive_whenSudoWouldPrompt_failsClearly() {
    var runner = new RecordingRunner(1);

    assertThatThrownBy(() -> SudoPrivilegePreflight.nonInteractive(runner).verify())
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("non-interactive sudo validation failed");
  }

  private static final class RecordingRunner implements ShellRunner {

    private final int exitCode;
    private List<String> command = List.of();

    private RecordingRunner(int exitCode) {
      this.exitCode = exitCode;
    }

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      this.command = List.copyOf(command);
      return new ProcessResult(exitCode, "", "", Duration.ZERO);
    }
  }
}
