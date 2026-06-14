package dev.sysboot.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class CliExitCodeTest {

  @Test
  void helpReturnsSuccess() {
    CliResult result = execute("--help");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Usage: fluxion");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void versionReturnsSuccess() {
    CliResult result = execute("--version");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("fluxion");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void unknownCommandReturnsInvalidInput() {
    CliResult result = execute("unknown");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Error:");
    assertThat(result.stderr()).contains("Usage: fluxion");
  }

  @Test
  void missingConfigReturnsConfigurationError() {
    CliResult result = execute("validate", "--no-tui", "-c", "/tmp/fluxion-does-not-exist.yaml");

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stderr()).contains("Error:");
    assertThat(result.stderr()).contains("File does not exist");
    assertThat(result.stderr()).doesNotContain("Exception");
  }

  @Test
  void classifiedCommandFailureReturnsConfiguredExitCode() {
    CliResult result = execute("state", "forget", "--profile", "default");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Specify --phase or --item");
    assertThat(result.stderr()).doesNotContain("Exception");
  }

  private CliResult execute(String... args) {
    CommandLine commandLine = Main.commandLine();
    var stdout = new StringWriter();
    var stderr = new StringWriter();
    commandLine.setOut(new PrintWriter(stdout));
    commandLine.setErr(new PrintWriter(stderr));

    int exitCode = commandLine.execute(args);

    return new CliResult(exitCode, stdout.toString(), stderr.toString());
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
