package dev.sysboot.cli.error;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.executor.ExecutionCancelledException;
import dev.sysboot.executor.StateWriteException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CliExceptionHandlerTest {

  @Test
  void stateWriteFailureReturnsIoErrorWithoutAStackTrace() {
    var commandLine = new CommandLine(new StubCommand());
    var error = new StringWriter();
    commandLine.setErr(new PrintWriter(error, true));

    int exitCode =
        new CliExceptionHandler()
            .handleExecutionException(
                new StateWriteException("state directory is read-only", new IOException("denied")),
                commandLine,
                null);

    assertThat(exitCode).isEqualTo(ExitCode.IO_ERROR.value());
    assertThat(error.toString())
        .contains("Error: state directory is read-only")
        .doesNotContain("StateWriteException")
        .doesNotContain("at dev.sysboot");
  }

  @Test
  void executionFailureWithTerminalControlsAndSecrets_isSanitized() {
    var commandLine = new CommandLine(new StubCommand());
    var error = new StringWriter();
    commandLine.setErr(new PrintWriter(error, true));

    new CliExceptionHandler()
        .handleExecutionException(
            new IllegalArgumentException(
                "invalid\nFORGED\u001B]8;;https://evil.test\u0007 PASSWORD=hunter2"),
            commandLine,
            null);

    assertThat(error.toString())
        .contains("Error: invalid FORGED")
        .contains("PASSWORD=<redacted>")
        .doesNotContain("\nFORGED", "\u001B", "\u0007", "https://evil.test", "hunter2");
  }

  @Test
  void parseFailureWithTerminalControlsAndSecrets_isSanitized() {
    var commandLine = new CommandLine(new StubCommand());
    var error = new StringWriter();
    commandLine.setErr(new PrintWriter(error, true));
    var exception =
        new CommandLine.ParameterException(
            commandLine, "invalid\nFORGED\u001B]8;;https://evil.test\u0007 PASSWORD=hunter2");

    new CliExceptionHandler().handleParseException(exception, new String[0]);

    assertThat(error.toString())
        .contains("Error: invalid FORGED")
        .contains("PASSWORD=<redacted>")
        .doesNotContain("\nFORGED", "\u001B", "\u0007", "https://evil.test", "hunter2");
  }

  @Test
  void typedCancellationReturnsShellCancellationExitCode() {
    var commandLine = new CommandLine(new StubCommand());
    var error = new StringWriter();
    commandLine.setErr(new PrintWriter(error, true));

    int exitCode =
        new CliExceptionHandler()
            .handleExecutionException(new ExecutionCancelledException(), commandLine, null);

    assertThat(exitCode).isEqualTo(ExitCode.CANCELLED.value());
    assertThat(error.toString()).contains("Bootstrap cancelled");
  }

  @CommandLine.Command(name = "stub")
  private static final class StubCommand {}
}
