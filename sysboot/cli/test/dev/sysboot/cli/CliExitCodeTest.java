package dev.sysboot.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.cli.error.ExitCode;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.StateEntry;
import dev.sysboot.executor.JsonStateRepository;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
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
    assertThat(result.stdout()).contains("apply");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void versionReturnsSuccess() {
    CliResult result = execute("--version");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).isEqualTo("fluxion 1.0.0\n");
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
  void runAlias_executesApplyDryRunPath() throws Exception {
    Path config = writeConfig();

    CliResult result =
        executeCapturingSystemOut("run", "--no-tui", "--dry-run", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("DRY-RUN").contains("git");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void applyDryRun_emitsNoMutatingExecution() throws Exception {
    Path config = writeConfig();

    CliResult result =
        executeCapturingSystemOut(
            "apply", "--no-tui", "--dry-run", "-c", config.toString(), "--yes");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("DRY-RUN").contains("dnf install -y git");
    assertThat(result.stdout()).doesNotContain("OK (");
    assertThat(result.stderr()).isEmpty();
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
        execute("status", "--resume-command", "-c", config.toString(), "--profile", "default");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("fluxion apply --no-tui")
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
  void lint_outputsAdvisoryProfileScore() throws Exception {
    Path config = writeBinaryWithoutChecksumConfig();

    CliResult result = execute("lint", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("Quality score:")
        .contains("warning safety")
        .contains("checksum")
        .contains("info reproducibility");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void lint_whenFormatJson_outputsMachineReadableFindings() throws Exception {
    Path config = writeShellConfig("/bin/sh");

    CliResult result = execute("lint", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"profileName\":\"test\"")
        .contains("\"score\"")
        .contains("\"category\":\"recoverability\"")
        .contains("\"path\":\"jobs[0].steps[0].probeCommand\"");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void plan_whenFormatJson_outputsStructuredPlan() throws Exception {
    Path config = writeConfig();

    CliResult result = execute("plan", "--no-tui", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"profileName\":\"test\"")
        .contains("\"phases\"")
        .contains("\"modules\"")
        .contains("\"commandPreview\"");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void plan_whenShowCommands_outputsCommandPreviews() throws Exception {
    Path config = writeConfig();

    CliResult result = execute("plan", "--show-commands", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("Execution plan for: test")
        .contains("$ sudo dnf install -y git");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void graph_outputsMermaidPhaseDag() throws Exception {
    Path config = writeDependentPhaseConfig();

    CliResult result = execute("graph", "--format", "mermaid", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("flowchart TD")
        .contains("p_base[\"base\"]")
        .contains("p_base --> p_desktop");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void graph_whenFormatJson_outputsPhaseEdges() throws Exception {
    Path config = writeDependentPhaseConfig();

    CliResult result = execute("graph", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"profileName\":\"test\"")
        .contains("\"edges\"")
        .contains("\"from\":\"base\"")
        .contains("\"to\":\"desktop\"");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void diff_whenFormatJson_outputsConfiguredChanges() throws Exception {
    Path config = writeMissingBinaryConfig();

    CliResult result = execute("diff", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"profileName\":\"test\"")
        .contains("\"changes\"")
        .contains("\"status\":\"configured-missing\"")
        .contains("/definitely/missing/fluxion-rg");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void explain_whenItemRequested_outputsStatusAndPreview() throws Exception {
    Path config = writeConfig();

    CliResult result =
        execute("explain", "--format", "json", "--item", "git", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"kind\":\"item\"")
        .contains("\"key\":\"git\"")
        .contains("\"phaseName\":\"base\"")
        .contains("\"moduleName\":\"tools\"")
        .contains("\"commandPreview\":[\"sudo\",\"dnf\",\"install\",\"-y\",\"git\"]");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void explain_whenPhaseRequested_outputsPhaseItems() throws Exception {
    Path config = writeConfig();

    CliResult result =
        execute("explain", "--format", "json", "--phase", "base", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"kind\":\"phase\"")
        .contains("\"phaseName\":\"base\"")
        .contains("\"restartEffect\":\"none\"")
        .contains("\"items\"")
        .contains("\"key\":\"git\"");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void explain_whenSelectorMissing_returnsInvalidInput() throws Exception {
    Path config = writeConfig();

    CliResult result = execute("explain", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Specify exactly one of --phase or --item");
  }

  @Test
  void list_whenFormatJson_outputsModuleList() throws Exception {
    Path config = writeConfig();

    CliResult result = execute("list", "--no-tui", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"profileName\":\"test\"")
        .contains("\"type\":\"packages\"")
        .contains("\"itemCount\":1");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void status_whenFormatJson_outputsProbeReport() throws Exception {
    Path config = writeConfig();

    CliResult result = execute("status", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"profileName\":\"test\"")
        .contains("\"summary\"")
        .contains("\"items\"");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void status_whenSummary_outputsOnlyCounts() throws Exception {
    Path config = writeBinaryWithoutChecksumConfig();

    CliResult result = execute("status", "--summary", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Configured missing:").contains("Version drift:");
    assertThat(result.stdout()).doesNotContain("/usr/local/bin/rg");
  }

  @Test
  void status_whenMissingFilter_outputsOnlyMissingItems() throws Exception {
    Path config = writeBinaryWithoutChecksumConfig();

    CliResult result = execute("status", "--missing", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"status\":\"configured-missing\"")
        .doesNotContain("\"status\":\"state-only\"");
  }

  @Test
  void status_whenVersionDriftFilter_outputsOnlyVersionDriftItems() throws Exception {
    Path config = writeBinaryWithoutChecksumConfig();

    CliResult result =
        execute("status", "--version-drift", "--format", "json", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("\"items\":[]").doesNotContain("configured-missing");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void status_whenStateOnlyFilter_outputsStateEntriesAbsentFromConfig() throws Exception {
    Path config = writeShellConfig("/bin/sh");
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.save(
          new BootstrapState(
              "default",
              Instant.now(),
              "1.0.0",
              List.of(
                  new StateEntry(
                      "default",
                      "old-tools",
                      "old-package",
                      ItemType.PACKAGE,
                      Instant.now(),
                      Optional.empty(),
                      Optional.empty())),
              List.of()));

      CliResult result =
          execute("status", "--state-only", "--format", "json", "-c", config.toString());

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("\"key\":\"old-package\"")
          .contains("\"status\":\"state-only\"");
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void stateShow_whenFormatJsonAndMissingState_outputsEmptyState() {
    CliResult result = execute("state", "show", "--format", "json", "default");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("\"profileName\":\"default\"")
        .contains("\"phases\":[]")
        .contains("\"items\":[]");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void stateShow_whenConfigProvided_outputsNextIncompletePhase() throws Exception {
    Path config = writeDependentPhaseConfig();
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.save(
          new BootstrapState(
              "default",
              Instant.now(),
              "1.0.0",
              List.of(),
              List.of(new PhaseStateEntry("base", PhaseStatus.COMPLETED, Instant.now()))));

      CliResult result =
          execute("state", "show", "-c", config.toString(), "--format", "json", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout()).contains("\"nextPhase\":\"desktop\"");
      assertThat(result.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void stateShow_whenConfigOmitted_keepsLegacyOutputShape() throws Exception {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.save(
          new BootstrapState(
              "default",
              Instant.now(),
              "1.0.0",
              List.of(),
              List.of(new PhaseStateEntry("base", PhaseStatus.COMPLETED, Instant.now()))));

      CliResult result = execute("state", "show", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout()).contains("Profile: default").doesNotContain("Next phase:");
      assertThat(result.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void generate_whenOutputExistsWithoutForce_returnsInvalidInput() throws Exception {
    Path generated = tempDir.resolve("generated.yaml");
    Files.writeString(generated, "existing");

    CliResult result = execute("generate", "--os", "fedora", "--output", generated.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Output file already exists");
  }

  @Test
  void snapshot_writesReviewRequiredInventory() throws Exception {
    Path snapshot = tempDir.resolve("snapshot.json");

    CliResult result = execute("snapshot", "--output", snapshot.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Snapshot written:");
    assertThat(snapshot).exists();
    assertThat(Files.readString(snapshot))
        .contains("\"schemaVersion\":1")
        .contains("\"reviewRequired\":true")
        .contains("\"packageManagers\"")
        .contains("\"toolchains\"");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void snapshot_whenOutputExistsWithoutForce_returnsInvalidInput() throws Exception {
    Path snapshot = tempDir.resolve("snapshot.json");
    Files.writeString(snapshot, "{}");

    CliResult result = execute("snapshot", "--output", snapshot.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Output file already exists");
  }

  @Test
  void importPackages_writesReviewRequiredFragment() throws Exception {
    Assumptions.assumeTrue(hasSupportedPackageDatabase());
    Path output = tempDir.resolve("packages.yaml");

    CliResult result = execute("import", "packages", "--from-host", "--output", output.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Imported packages:");
    assertThat(output).exists();
    assertThat(Files.readString(output))
        .contains("Review required")
        .contains("type: packages")
        .contains("packageManager:")
        .contains("packages:");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void importPackages_whenOutputExistsWithoutForce_returnsInvalidInput() throws Exception {
    Path output = tempDir.resolve("packages.yaml");
    Files.writeString(output, "existing");

    CliResult result = execute("import", "packages", "--from-host", "--output", output.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("Output file already exists");
  }

  @Test
  void importFlatpaks_writesReviewRequiredFragment() throws Exception {
    Assumptions.assumeTrue(hasInstalledFlatpaks());
    Path output = tempDir.resolve("flatpaks.yaml");

    CliResult result = execute("import", "flatpaks", "--from-host", "--output", output.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Imported Flatpaks:");
    assertThat(output).exists();
    assertThat(Files.readString(output))
        .contains("Review required")
        .contains("type: flatpak")
        .contains("remote:")
        .contains("appIds:");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void importFlatpaks_whenOutputExistsWithoutForce_returnsInvalidInput() throws Exception {
    Path output = tempDir.resolve("flatpaks.yaml");
    Files.writeString(output, "existing");

    CliResult result = execute("import", "flatpaks", "--from-host", "--output", output.toString());

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

  private CliResult executeCapturingSystemOut(String... args) {
    PrintStream originalOut = System.out;
    var stdout = new ByteArrayOutputStream();
    System.setOut(new PrintStream(stdout, true, StandardCharsets.UTF_8));
    try {
      CliResult result = execute(args);
      return new CliResult(
          result.exitCode(), stdout.toString(StandardCharsets.UTF_8), result.stderr());
    } finally {
      System.setOut(originalOut);
    }
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

  private Path writeDependentPhaseConfig() throws IOException {
    Path config = tempDir.resolve("dependent-profile.yaml");
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
          - name: desktop
            dependsOn: [base]
            steps:
              - type: flatpak
                name: apps
                remote: flathub
                appIds: [org.mozilla.firefox]
        """);
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

  private Path writeMissingBinaryConfig() throws IOException {
    Path config = tempDir.resolve("missing-binary-profile.yaml");
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
                binaryName: fluxion-rg
                url: https://example.test/rg.tar.gz
                installPath: /definitely/missing/fluxion-rg
                checksum:
                  algorithm: sha256
                  value: abcdef
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

  private boolean hasSupportedPackageDatabase() {
    return commandExists("rpm") || commandExists("pacman") || commandExists("dpkg-query");
  }

  private boolean hasInstalledFlatpaks() {
    if (!commandExists("flatpak")) {
      return false;
    }
    try {
      Process process =
          new ProcessBuilder("flatpak", "list", "--app", "--columns=application")
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return false;
      }
      return process.exitValue() == 0 && !process.inputReader().lines().toList().isEmpty();
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private boolean commandExists(String command) {
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return false;
    }
    return Arrays.stream(path.split(File.pathSeparator))
        .map(Path::of)
        .map(dir -> dir.resolve(command))
        .anyMatch(Files::isExecutable);
  }

  private record CliResult(int exitCode, String stdout, String stderr) {}
}
