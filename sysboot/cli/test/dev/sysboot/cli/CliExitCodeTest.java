package dev.sysboot.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.cli.error.ExitCode;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CliExitCodeTest {

  @TempDir Path tempDir;

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

  @Test
  void statePath_usesFluxionStateDirectory() {
    CliResult result = execute("state", "path", "default");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains(".local/share/fluxion/default.state.json");
  }

  @Test
  void stateReset_deletesFluxionAndLegacyStateFiles() throws Exception {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      Path stateFile = tempDir.resolve(".local/share/fluxion/default.state.json");
      Path legacyStateFile = tempDir.resolve(".local/share/sysboot/default.state.json");
      Files.createDirectories(stateFile.getParent());
      Files.createDirectories(legacyStateFile.getParent());
      Files.writeString(stateFile, "{}");
      Files.writeString(legacyStateFile, "{}");

      CliResult result = execute("state", "reset", "default", "--force");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout()).contains("State reset for profile: default");
      assertThat(stateFile).doesNotExist();
      assertThat(legacyStateFile).doesNotExist();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void run_whenPhaseDoesNotExist_returnsConfigurationError() throws Exception {
    Path config = writeConfig();

    CliResult result =
        execute("run", "--no-tui", "--dry-run", "-c", config.toString(), "--phase", "missing");

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stderr()).contains("Unknown phase 'missing'");
    assertThat(result.stderr()).contains("base");
  }

  @Test
  void run_whenFromPhaseDoesNotExist_returnsConfigurationError() throws Exception {
    Path config = writeConfig();

    CliResult result =
        execute("run", "--no-tui", "--dry-run", "-c", config.toString(), "--from-phase", "missing");

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stderr()).contains("Unknown phase 'missing'");
    assertThat(result.stderr()).contains("base");
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

  private Path writeConfig() throws IOException {
    Path config = tempDir.resolve("profile.yaml");
    Files.writeString(
        config,
        """
        profile: test
        os:
          type: fedora
          release: "44"
        jobs:
          - name: base
            steps:
              - type: packages
                name: tools
                packageManager: dnf
                packages: [git]
        """);
    return config;
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
