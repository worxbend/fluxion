package dev.sysboot.cli.command;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.Main;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.executor.JsonStateRepository;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ApplyCommandValidationTest {

  @Test
  void apply_whenJobGraphContainsCycle_failsBeforeResettingState(@TempDir Path tempDir)
      throws IOException {
    Path config = writeCycleConfig(tempDir);

    assertInvalidConfigHasNoStateEffect(tempDir, config, "Cycle in job dependency graph");
  }

  @Test
  void apply_whenPackageManagerDoesNotMatchTarget_failsBeforeResettingState(@TempDir Path tempDir)
      throws IOException {
    Path config = writeTargetMismatchConfig(tempDir);

    assertInvalidConfigHasNoStateEffect(
        tempDir, config, "Package manager dnf is not valid for target debian");
  }

  @ParameterizedTest
  @ValueSource(strings = {"plan", "dry-run"})
  void previewCommand_whenPackageManagerDoesNotMatchTarget_failsBeforePlanning(
      String command, @TempDir Path tempDir) throws IOException {
    Path config = writeTargetMismatchConfig(tempDir);

    CliResult result = execute(command, "--profile", "preview", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stderr())
        .contains("Config validation failed")
        .contains("Package manager dnf is not valid for target debian");
    assertThat(result.stdout()).isEmpty();
  }

  private void assertInvalidConfigHasNoStateEffect(
      Path tempDir, Path config, String expectedMessage) throws IOException {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      String profile = "validation-sentinel";
      Path stateFile = new JsonStateRepository(new ObjectMapper()).path(profile);
      Files.createDirectories(stateFile.getParent());
      Files.writeString(stateFile, "sentinel");

      CliResult result =
          execute(
              "apply", "--no-tui", "--reset-state", "--profile", profile, "-c", config.toString());

      assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
      assertThat(result.stderr()).contains("Config validation failed").contains(expectedMessage);
      assertThat(stateFile).hasContent("sentinel");
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  private Path writeCycleConfig(Path tempDir) throws IOException {
    return write(
        tempDir.resolve("cycle.yaml"),
        """
        profile: invalid-cycle
        os:
          type: fedora
          release: "44"
        jobs:
          - name: base
            dependsOn: [desktop]
            steps:
              - type: packages
                name: base-packages
                packageManager: dnf
                packages: [git]
          - name: desktop
            dependsOn: [base]
            steps:
              - type: packages
                name: desktop-packages
                packageManager: dnf
                packages: [curl]
        """);
  }

  private Path writeTargetMismatchConfig(Path tempDir) throws IOException {
    return write(
        tempDir.resolve("target-mismatch.yaml"),
        """
        profile: invalid-target-manager
        os:
          type: debian
          release: "12"
        jobs:
          - name: base
            steps:
              - type: packages
                name: base-packages
                packageManager: dnf
                packages: [git]
        """);
  }

  private Path write(Path path, String content) throws IOException {
    Files.writeString(path, content);
    return path;
  }

  private CliResult execute(String... args) {
    var commandLine = Main.commandLine();
    var stdout = new StringWriter();
    var stderr = new StringWriter();
    commandLine.setOut(new PrintWriter(stdout));
    commandLine.setErr(new PrintWriter(stderr));
    int exitCode = commandLine.execute(args);
    return new CliResult(exitCode, stdout.toString(), stderr.toString());
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
