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

  @Test
  void statusResumeCommand_printsNextIncompletePhase() throws Exception {
    Path config = writeConfig();

    CliResult result =
        execute(
            "status",
            "--resume-command",
            "-c",
            config.toString(),
            "--profile",
            "default");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("fluxion run --no-tui")
        .contains("-c " + config)
        .contains("--profile default")
        .contains("--skip-already-installed")
        .contains("--from-phase base");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void generate_writesValidStarterConfig() throws Exception {
    Path generated = tempDir.resolve("generated.yaml");

    CliResult generate =
        execute(
            "generate",
            "--os",
            "fedora",
            "--profile",
            "generated",
            "--preset",
            "developer",
            "--output",
            generated.toString());

    assertThat(generate.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(generate.stdout()).contains("Generated config:");
    assertThat(generated).exists();
    assertThat(Files.readString(generated))
        .contains("profile: generated")
        .contains("packageManager: dnf")
        .doesNotContain("you@example.com")
        .doesNotContain("Your Name");

    CliResult validate = execute("validate", "--no-tui", "-c", generated.toString());
    assertThat(validate.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(validate.stdout()).contains("Config is valid");
  }

  @Test
  void validate_whenFormatJson_outputsJsonReport() throws Exception {
    Path config = writeConfig();

    CliResult result = execute("validate", "--no-tui", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"profileName\":\"test\"")
        .contains("\"valid\":true")
        .contains("\"issues\":[]");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void validate_whenStrictWarnings_returnsConfigurationError() throws Exception {
    Path config = writeBinaryWithoutChecksumConfig();

    CliResult result = execute("validate", "--no-tui", "--strict", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stdout()).contains("warning").contains("checksum");
    assertThat(result.stderr()).contains("Config validation failed");
  }

  @Test
  void validate_whenPackageManagerDoesNotMatchTarget_reportsError() throws Exception {
    Path config = writeWrongPackageManagerConfig();

    CliResult result = execute("validate", "--no-tui", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stdout()).contains("jobs[0].steps[0].packageManager").contains("apt");
    assertThat(result.stderr()).contains("Config validation failed");
  }

  @Test
  void generate_whenOutputExistsWithoutForce_returnsInvalidInput() throws Exception {
    Path generated = tempDir.resolve("generated.yaml");
    Files.writeString(generated, "existing");

    CliResult result =
        execute("generate", "--os", "fedora", "--output", generated.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Output file already exists");
  }

  @Test
  void doctor_whenConfigIsReady_returnsSuccessWithReport() throws Exception {
    Path config = writeShellConfig("/bin/sh");
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult result =
          execute("doctor", "--skip-network", "-c", config.toString(), "--profile", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("[pass] config file")
          .contains("[pass] state directory")
          .contains("[pass] shell");
      assertThat(result.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void doctor_whenConfiguredShellIsMissing_returnsDependencyError() throws Exception {
    Path config = writeShellConfig("/definitely/missing/fluxion-shell");

    CliResult result = execute("doctor", "--skip-network", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.EXTERNAL_DEPENDENCY_ERROR.value());
    assertThat(result.stdout()).contains("[fail] shell");
    assertThat(result.stderr()).contains("Doctor found");
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

  private Path writeShellConfig(String shell) throws IOException {
    Path config = tempDir.resolve("shell-profile.yaml");
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
              - type: shell-command
                name: echo
                shell: "%s"
                commands:
                  - "echo ready"
        """
            .formatted(shell));
    return config;
  }

  private Path writeBinaryWithoutChecksumConfig() throws IOException {
    Path config = tempDir.resolve("binary-profile.yaml");
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
              - type: compiled-binary
                name: ripgrep
                binaryName: rg
                url: https://example.test/rg.tar.gz
                installPath: /usr/local/bin/rg
        """);
    return config;
  }

  private Path writeWrongPackageManagerConfig() throws IOException {
    Path config = tempDir.resolve("wrong-package-manager.yaml");
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
                packageManager: apt
                packages: [git]
        """);
    return config;
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
