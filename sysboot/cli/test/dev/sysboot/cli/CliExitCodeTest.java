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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  void subcommandHelpReturnsSuccess() {
    CliResult result = execute("apply", "--help");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Usage: fluxion apply");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void nestedSubcommandHelpReturnsSuccessEvenWhenCommandHasRequiredParameters() {
    CliResult result = execute("tools", "install", "--help");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Usage: fluxion tools install").contains("--release");
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
  void statePath_whenProfileTraverses_returnsInvalidInput() {
    CliResult result = execute("state", "path", "../outside");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("safe slug").doesNotContain("Exception");
  }

  @Test
  void stateReset_whenProfileTraverses_returnsInvalidInput() {
    CliResult result = execute("state", "reset", "../outside", "--force");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("safe slug").doesNotContain("Exception");
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
  void stateReset_whenStateFileIsDanglingSymlink_removesLink() throws Exception {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      Path stateFile = tempDir.resolve(".local/share/fluxion/default.state.json");
      Files.createDirectories(stateFile.getParent());
      createSymlinkOrSkip(stateFile, tempDir.resolve("missing-state-target"));

      CliResult result = execute("state", "reset", "default", "--force");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout()).contains("State reset for profile: default");
      assertThat(Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)).isFalse();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void stateForget_whenItemKeyIsAmbiguous_failsWithoutDeletingEitherEntry() {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.save(stateWithSharedItemKey());

      CliResult result = execute("state", "forget", "--profile", "default", "--item", "shared");

      assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
      assertThat(result.stderr()).contains("ambiguous").contains("--module").contains("--type");
      assertThat(repo.load("default").orElseThrow().entries()).hasSize(2);
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void stateForget_whenItemIsQualified_deletesOnlyCanonicalIdentity() {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.save(stateWithSharedItemKey());

      CliResult result =
          execute(
              "state",
              "forget",
              "--profile",
              "default",
              "--item",
              "shared",
              "--module",
              "core",
              "--type",
              "package");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout()).contains("core/shared");
      assertThat(repo.load("default").orElseThrow().entries())
          .extracting(StateEntry::moduleName)
          .containsExactly("desktop");
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
  void run_whenSelectingDependentPhase_removesDependenciesOutsideSelection() throws Exception {
    Path config = writeDependentPhaseConfig();

    CliResult result =
        executeCapturingSystemOut(
            "run", "--no-tui", "--dry-run", "-c", config.toString(), "--phase", "desktop");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("org.mozilla.firefox")
        .doesNotContain("dnf install -y git");
    assertThat(result.stderr()).isEmpty();
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
    assertThat(result.stdout())
        .contains("Operation: apply")
        .contains("Mode: dry-run")
        .contains("DRY-RUN")
        .contains("dnf install -y git")
        .contains("Final counts: ok=0 failed=0 skipped=0 dry_run=1 paused=0");
    assertThat(result.stdout()).doesNotContain("OK (");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void apply_whenExecutedStepFails_returnsDependencyError() throws Exception {
    Path config = writeFailingShellConfig();

    CliResult result =
        executeCapturingSystemOut("apply", "--no-tui", "-c", config.toString(), "--yes");

    assertThat(result.exitCode()).isEqualTo(ExitCode.EXTERNAL_DEPENDENCY_ERROR.value());
    assertThat(result.stdout()).contains("FAILED").contains("failed=1");
    assertThat(result.stderr()).contains("Bootstrap failed in phase(s): base");
  }

  @Test
  void applyDryRun_whenConsoleMissing_usesPlainOutput() throws Exception {
    Assumptions.assumeTrue(System.console() == null);
    Path config = writeConfig();

    CliResult result =
        executeCapturingSystemOut("apply", "--dry-run", "-c", config.toString(), "--yes");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("[PHASE").contains("DRY-RUN").contains("git");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void apply_whenProfileDefaultsToDryRun_usesPlainDryRunWithoutMutation() throws Exception {
    Path mutation = tempDir.resolve("must-not-exist");
    Path config = writeDryRunDefaultConfig(mutation);

    CliResult result =
        executeCapturingSystemOut("apply", "--no-tui", "-c", config.toString(), "--yes");

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Mode: dry-run").contains("DRY-RUN");
    assertThat(mutation).doesNotExist();
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void apply_whenProfileDefaultsToDryRun_doesNotResetState() throws Exception {
    Path config = writeDryRunDefaultConfig(tempDir.resolve("must-not-exist"));
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      Path stateFile = new JsonStateRepository(new ObjectMapper()).path("default");
      Files.createDirectories(stateFile.getParent());
      Files.writeString(stateFile, "{}");

      CliResult result =
          executeCapturingSystemOut(
              "apply", "--no-tui", "--reset-state", "-c", config.toString(), "--yes");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout()).contains("Mode: dry-run");
      assertThat(stateFile).exists();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void applyDryRun_withSkipState_neverRepairsPermissionsOrMutatesStateLayout() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileStore(tempDir)
            .supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class));
    Path mutation = tempDir.resolve("must-not-exist");
    Path config = writeDryRunDefaultConfig(mutation);
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repository = new JsonStateRepository(new ObjectMapper());
      repository.save(BootstrapState.empty("default", "1.0.0"));
      Path stateFile = repository.path("default");
      Path stateRoot = stateFile.getParent();
      Files.setPosixFilePermissions(
          stateRoot, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
      Files.setPosixFilePermissions(
          stateFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
      var timestamp = java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));
      Files.setLastModifiedTime(stateRoot, timestamp);
      Files.setLastModifiedTime(stateFile, timestamp);
      List<String> layout = directoryLayout(tempDir);

      CliResult result =
          executeCapturingSystemOut(
              "apply",
              "--no-tui",
              "--dry-run",
              "--skip-already-installed",
              "-c",
              config.toString(),
              "--yes");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(Files.getPosixFilePermissions(stateRoot))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
      assertThat(Files.getPosixFilePermissions(stateFile))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
      assertThat(Files.getLastModifiedTime(stateRoot)).isEqualTo(timestamp);
      assertThat(Files.getLastModifiedTime(stateFile)).isEqualTo(timestamp);
      assertThat(directoryLayout(tempDir)).isEqualTo(layout);
      assertThat(mutation).doesNotExist();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void plan_withSkipState_neverRepairsPermissionsOrMutatesStateLayout() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileStore(tempDir)
            .supportsFileAttributeView(java.nio.file.attribute.PosixFileAttributeView.class));
    Path mutation = tempDir.resolve("must-not-exist");
    Path config = writeDryRunDefaultConfig(mutation);
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repository = new JsonStateRepository(new ObjectMapper());
      repository.save(BootstrapState.empty("default", "1.0.0"));
      Path stateFile = repository.path("default");
      Path stateRoot = stateFile.getParent();
      Files.setPosixFilePermissions(
          stateRoot, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
      Files.setPosixFilePermissions(
          stateFile, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
      var timestamp = java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));
      Files.setLastModifiedTime(stateRoot, timestamp);
      Files.setLastModifiedTime(stateFile, timestamp);
      List<String> layout = directoryLayout(tempDir);

      CliResult result = execute("plan", "--skip-already-installed", "-c", config.toString());

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(Files.getPosixFilePermissions(stateRoot))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
      assertThat(Files.getPosixFilePermissions(stateFile))
          .isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
      assertThat(Files.getLastModifiedTime(stateRoot)).isEqualTo(timestamp);
      assertThat(Files.getLastModifiedTime(stateFile)).isEqualTo(timestamp);
      assertThat(directoryLayout(tempDir)).isEqualTo(layout);
      assertThat(mutation).doesNotExist();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void applyHelp_doesNotAdvertiseRemovedParallelPhasesOption() {
    CommandLine apply = Main.commandLine().getSubcommands().get("apply");

    assertThat(apply.getUsageMessage()).doesNotContain("--parallel-phases");
  }

  @Test
  void apply_whenParallelPhasesRequested_rejectsRemovedOption() {
    CliResult result = execute("apply", "--parallel-phases");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr())
        .contains("Unknown option: '--parallel-phases'")
        .contains("Usage: fluxion apply");
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
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
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
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"minimal", "developer", "desktop", "dotfiles"})
  void generate_eachPresetWritesAValidStarterConfig(String preset) throws Exception {
    Path generated = tempDir.resolve("generated-" + preset + ".yaml");

    CliResult generate =
        execute(
            "generate",
            "--os",
            "fedora",
            "--profile",
            "generated",
            "--preset",
            preset,
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
    if ("dotfiles".equals(preset)) {
      assertThat(Files.readString(generated)).contains("installerVersion: \"v0.4.2\"");
    }

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
    Path config = writeDuplicatePackageConfig();

    CliResult result = execute("validate", "--no-tui", "--strict", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stdout()).contains("warning").contains("Duplicate package");
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
  void validate_whenChecksumUrlIsTheOnlyBinaryTrustMetadata_reportsError() throws Exception {
    Path config = writeBinaryWithUnboundChecksumUrlConfig();

    CliResult result = execute("validate", "--no-tui", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stdout()).contains("literal SHA-256").contains("supplemental");
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
  void plan_whenRemoteScriptUsesSignedUrl_hidesPrivateUrlComponentsInTextAndJson()
      throws Exception {
    Path config = writeWorkstationProfileWithSignedScriptUrl();

    CliResult text = execute("plan", "--no-tui", "--show-commands", "-c", config.toString());
    CliResult json =
        execute("plan", "--no-tui", "--show-commands", "--format", "json", "-c", config.toString());

    for (CliResult result : List.of(text, json)) {
      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("https://example.test/install.sh")
          .doesNotContain("X-Amz-Signature")
          .doesNotContain("sensitive-signature")
          .doesNotContain("sensitive-fragment");
      assertThat(result.stderr()).isEmpty();
    }
    assertThat(json.stdout())
        .contains("\"displayName\":\"https://example.test/install.sh\"")
        .contains("\"commandPreview\"");
  }

  @Test
  void plan_whenWorkstationProfileHasSources_showsSourceCommandsBeforePackages() throws Exception {
    Path config = writeWorkstationProfileWithDnfSource();

    CliResult result = execute("plan", "--show-commands", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout()).contains("Source setup:");
    assertThat(result.stdout())
        .containsSubsequence(
            "$ sysboot-source-setup dnf docker no-remote-artifact", "$ sudo dnf install -y git");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void plan_whenFormatTable_outputsRows() throws Exception {
    Path config = writeConfig();

    CliResult result = execute("plan", "--format", "table", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("PHASE")
        .contains("MODULE")
        .contains("base")
        .contains("git");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void plan_whenFormatTree_outputsHierarchy() throws Exception {
    Path config = writeConfig();

    CliResult result =
        execute("plan", "--format", "tree", "--show-commands", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("Execution plan for: test")
        .contains("base [no deps]")
        .contains("tools (packages)")
        .contains("$ sudo dnf install -y git");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void plan_whenWorkstationProfileEntriesSkipped_outputsReasons() throws Exception {
    Path config = writeWorkstationProfileWithSkippedPlan();

    CliResult result = execute("plan", "--no-tui", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(result.stdout())
        .contains("Execution plan for: workstation-skip-test")
        .contains("git")
        .contains("Skipped WorkstationProfile entries:")
        .contains("arch-only")
        .contains("when.distribution expected one of [no-such-os]");
    assertThat(result.stderr()).isEmpty();
  }

  @Test
  void dryRunAndApplyNoTuiDryRun_reportSameWorkstationProfileSelection() throws Exception {
    Path config = writeWorkstationProfileWithSkippedPlan();

    CliResult plan = execute("plan", "--no-tui", "-c", config.toString());
    CliResult dryRun = executeCapturingSystemOut("dry-run", "--no-tui", "-c", config.toString());
    CliResult apply =
        executeCapturingSystemOut(
            "apply", "--no-tui", "--dry-run", "-c", config.toString(), "--yes");

    assertThat(plan.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(dryRun.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertThat(apply.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
    assertWorkstationSelection(plan.stdout(), "plan");
    assertWorkstationSelection(dryRun.stdout(), "dry-run");
    assertWorkstationSelection(apply.stdout(), "apply");
    assertThat(dryRun.stdout())
        .contains("SKIPPED")
        .contains("arch-only")
        .contains("when.distribution expected one of [no-such-os]")
        .contains("DRY-RUN")
        .contains("git");
    assertThat(apply.stdout())
        .contains("SKIPPED")
        .contains("arch-only")
        .contains("when.distribution expected one of [no-such-os]")
        .contains("DRY-RUN")
        .contains("git");
    assertThat(plan.stderr()).isEmpty();
    assertThat(dryRun.stderr()).isEmpty();
    assertThat(apply.stderr()).isEmpty();
  }

  @Test
  void plainReporting_redactsSensitiveCommandAndFailureText() throws Exception {
    Path config = writeWorkstationProfileWithSensitiveCommand();
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult plan = execute("plan", "--show-commands", "--no-tui", "-c", config.toString());
      CliResult apply = executeCapturingSystemOut("apply", "--no-tui", "-c", config.toString());

      assertThat(plan.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(plan.stdout()).contains("token=<redacted>").doesNotContain("super-secret-token");
      assertThat(apply.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(apply.stdout())
          .contains("FAILED")
          .contains("token=<redacted>")
          .contains("Final counts: ok=0 failed=1 skipped=0 dry_run=0 paused=0")
          .doesNotContain("super-secret-token");
      assertThat(plan.stderr()).isEmpty();
      assertThat(apply.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void dryRun_whenWorkstationProfileInterrupt_doesNotCreateStateFile() throws Exception {
    Path config = writeWorkstationProfileWithInterrupt("next");
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult result = executeCapturingSystemOut("dry-run", "--no-tui", "-c", config.toString());

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("DRY-RUN")
          .contains("state-write")
          .contains("nextPlanEntry=after-pause");
      assertThat(result.stderr()).isEmpty();
      assertThat(new JsonStateRepository(new ObjectMapper()).path("default")).doesNotExist();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void apply_whenWorkstationProfileInterrupt_writesStateAndReturnsPauseCode() throws Exception {
    Path config = writeWorkstationProfileWithInterrupt("next");
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult result =
          executeCapturingSystemOut("apply", "--no-tui", "-c", config.toString(), "--yes");

      assertThat(result.exitCode()).isEqualTo(ExitCode.PAUSED.value());
      assertThat(result.stdout())
          .contains("PAUSED")
          .contains("Log out before continuing.")
          .contains("Run fluxion apply again.")
          .contains(".local/share/fluxion/default.state.json")
          .contains("Next plan entry: after-pause")
          .contains("Resume with: fluxion apply --no-tui")
          .contains("--from-phase manifest-plan");
      assertThat(result.stderr()).contains("Error: Log out before continuing.");
      BootstrapState state =
          new JsonStateRepository(new ObjectMapper()).load("default").orElseThrow();
      assertThat(state.nextPlanEntry()).contains("after-pause");
      assertThat(state.manifestIdentity()).contains("workstation-interrupt-test");
      assertThat(state.manifestFingerprint()).isPresent();
      assertThat(state.planEntryEntries())
          .extracting(entry -> entry.entryName() + ":" + entry.status())
          .containsExactly("pause-login:COMPLETED");
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void apply_whenInterruptUsesCustomExitCode_returnsThatCode() throws Exception {
    Path config = writeWorkstationProfileWithInterrupt("next", 42);
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult result =
          executeCapturingSystemOut("apply", "--no-tui", "-c", config.toString(), "--yes");

      assertThat(result.exitCode()).isEqualTo(42);
      assertThat(result.stdout()).contains("PAUSED").contains("Log out before continuing.");
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void apply_whenStateManifestFingerprintDiffers_rejectsUntilResetRequested() throws Exception {
    Path firstConfig = writeWorkstationProfileWithInterrupt("next");
    Path changedConfig = writeWorkstationProfileWithInterrupt("current");
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult first =
          executeCapturingSystemOut("apply", "--no-tui", "-c", firstConfig.toString(), "--yes");
      CliResult stale =
          executeCapturingSystemOut("apply", "--no-tui", "-c", changedConfig.toString(), "--yes");
      CliResult reset =
          executeCapturingSystemOut(
              "apply", "--no-tui", "--reset-state", "-c", changedConfig.toString(), "--yes");

      assertThat(first.exitCode()).isEqualTo(ExitCode.PAUSED.value());
      assertThat(stale.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
      assertThat(stale.stderr())
          .contains("Saved state is stale")
          .contains("manifest fingerprint")
          .contains("--reset-state");
      assertThat(reset.exitCode()).isEqualTo(ExitCode.PAUSED.value());
      assertThat(reset.stdout()).contains("Next plan entry: pause-login");
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void apply_whenGeneratedMultiPhaseResumeCommandRuns_usesFullManifestIdentity() throws Exception {
    Path baseMarker = tempDir.resolve("base-ran");
    Path desktopMarker = tempDir.resolve("desktop-ran");
    Path config = writeMultiPhaseResumeConfig(baseMarker, desktopMarker);
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult first =
          executeCapturingSystemOut("apply", "--no-tui", "-c", config.toString(), "--yes");
      String resumeCommand =
          first
              .stdout()
              .lines()
              .filter(line -> line.contains("Resume with: fluxion apply"))
              .map(line -> line.substring(line.indexOf("fluxion apply")))
              .findFirst()
              .orElseThrow();

      String[] commandParts = resumeCommand.split(" ");
      CliResult resumed =
          executeCapturingSystemOut(Arrays.copyOfRange(commandParts, 1, commandParts.length));

      assertThat(first.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(resumeCommand)
          .contains("--skip-already-installed")
          .contains("--from-phase desktop");
      assertThat(resumed.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(resumed.stderr()).isEmpty();
      assertThat(baseMarker).exists();
      assertThat(desktopMarker).exists();
      BootstrapState state =
          new JsonStateRepository(new ObjectMapper()).load("default").orElseThrow();
      assertThat(state.manifestIdentity()).contains("multi-phase-resume");
      assertThat(state.findPhaseEntry("desktop")).isPresent();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void apply_whenReprobeRequested_ignoresStalePhaseAndResumeState() throws Exception {
    Path beforeMarker = tempDir.resolve("reprobe-before");
    Path afterMarker = tempDir.resolve("reprobe-after");
    Path config = writeReprobeConfig(beforeMarker, afterMarker);
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repository = new JsonStateRepository(new ObjectMapper());
      repository.save(
          BootstrapState.empty("default", "1.0.0")
              .withPhaseEntry(
                  new PhaseStateEntry(
                      "base", PhaseStatus.COMPLETED, Instant.now(), Optional.of("old-phase")))
              .withNextPlanEntry(Optional.of("after"))
              .withManifestMetadata("reprobe-profile", "old-manifest"));

      CliResult result =
          executeCapturingSystemOut(
              "apply", "--no-tui", "--re-probe", "-c", config.toString(), "--yes");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stderr()).isEmpty();
      assertThat(beforeMarker).exists();
      assertThat(afterMarker).exists();
      BootstrapState refreshed = repository.load("default").orElseThrow();
      assertThat(refreshed.nextPlanEntry()).isEmpty();
      assertThat(refreshed.entries())
          .extracting(StateEntry::moduleName)
          .containsExactly("before", "after");
      assertThat(refreshed.manifestFingerprint())
          .hasValueSatisfying(value -> assertThat(value).doesNotContain("old-manifest"));
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void apply_whenGuardedCommandSelected_requiresYesBeforeMutation() throws Exception {
    Path marker = tempDir.resolve("guarded-command-ran");
    Path config = writeGuardedCommandConfig(marker);
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult denied = executeCapturingSystemOut("apply", "--no-tui", "-c", config.toString());
      CliResult preview =
          executeCapturingSystemOut("apply", "--no-tui", "--dry-run", "-c", config.toString());

      assertThat(denied.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
      assertThat(denied.stderr())
          .contains("Explicit confirmation required")
          .contains("guarded/guarded-command")
          .contains("--yes");
      assertThat(marker).doesNotExist();
      assertThat(preview.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(preview.stdout()).contains("DRY-RUN");
      assertThat(marker).doesNotExist();

      CliResult approved =
          executeCapturingSystemOut("apply", "--no-tui", "--yes", "-c", config.toString());

      assertThat(approved.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(approved.stderr()).isEmpty();
      assertThat(marker).exists();
    } finally {
      System.setProperty("user.home", originalHome);
    }
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
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult result = execute("state", "show", "--format", "json", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("\"profileName\":\"default\"")
          .contains("\"phases\":[]")
          .contains("\"items\":[]");
      assertThat(result.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void stateShow_whenLegacySourceContainsCredentials_redactsRequestSecrets() {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.save(stateWithSourceUrl("https://user:password@example.test/rg?token=secret#asset"));

      CliResult result = execute("state", "show", "--format", "json", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("https://example.test/rg")
          .doesNotContain("password")
          .doesNotContain("token")
          .doesNotContain("secret");
    } finally {
      System.setProperty("user.home", originalHome);
    }
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
              List.of(
                  new PhaseStateEntry(
                      "base",
                      PhaseStatus.COMPLETED,
                      Instant.now(),
                      Optional.empty(),
                      Optional.of("already applied")))));

      CliResult result =
          execute("state", "show", "-c", config.toString(), "--format", "json", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("\"nextPhase\":\"desktop\"")
          .contains("\"reason\":\"already applied\"");
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
  void reportLast_whenStateExists_outputsMarkdownReportWithResumeCommand() throws Exception {
    Path config = writeDependentPhaseConfig();
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.save(
          new BootstrapState(
              "default",
              Instant.parse("2026-06-01T10:00:00Z"),
              "1.0.0",
              List.of(
                  new StateEntry(
                      "default",
                      "ripgrep",
                      "/usr/local/bin/rg",
                      ItemType.COMPILED_BINARY,
                      Instant.parse("2026-06-01T10:01:00Z"),
                      Optional.of("14.1.1"),
                      Optional.of("a".repeat(64)),
                      Optional.of(
                          "https://user:password@example.test/rg.tar.gz?token=secret#asset"))),
              List.of(
                  new PhaseStateEntry(
                      "base", PhaseStatus.COMPLETED, Instant.parse("2026-06-01T10:02:00Z")))));

      CliResult result = execute("report", "last", "-c", config.toString(), "--profile", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("# Fluxion Run Report")
          .contains("fluxion apply --no-tui -c")
          .contains("--profile default")
          .contains("--skip-already-installed")
          .contains("--from-phase desktop")
          .contains("Last run: `2026-06-01T10:00:00Z`")
          .contains("State version: `1.0.0`")
          .contains("/usr/local/bin/rg")
          .contains("https://example.test/rg.tar.gz")
          .doesNotContain("password")
          .doesNotContain("token")
          .doesNotContain("secret");
      assertThat(result.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  private BootstrapState stateWithSourceUrl(String sourceUrl) {
    return new BootstrapState(
        "default",
        Instant.now(),
        "1.0.0",
        List.of(
            new StateEntry(
                "default",
                "ripgrep",
                "/usr/local/bin/rg",
                ItemType.COMPILED_BINARY,
                Instant.now(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(sourceUrl))),
        List.of());
  }

  @Test
  void reportLast_whenFormatHtml_outputsHtmlReport() throws Exception {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      var repo = new JsonStateRepository(new ObjectMapper());
      repo.save(
          new BootstrapState(
              "default",
              Instant.parse("2026-06-01T10:00:00Z"),
              "1.0.0",
              List.of(),
              List.of(new PhaseStateEntry("base", PhaseStatus.COMPLETED, Instant.now()))));

      CliResult result = execute("report", "last", "--format", "html", "--profile", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("<!doctype html>")
          .contains("<h1>Fluxion Run Report</h1>")
          .contains("<strong>Last run:</strong> 2026-06-01T10:00:00Z")
          .contains("<strong>State version:</strong> 1.0.0");
      assertThat(result.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void reportLast_whenStateMissing_returnsConfigurationError() {
    CliResult result = execute("report", "last", "--profile", "missing");

    assertThat(result.exitCode()).isEqualTo(ExitCode.CONFIGURATION_ERROR.value());
    assertThat(result.stderr()).contains("No state file found for profile: missing");
  }

  @Test
  void reportLast_whenProfileIsAbsolute_returnsInvalidInput() {
    CliResult result = execute("report", "last", "--profile", "/tmp/outside");

    assertThat(result.exitCode()).isEqualTo(ExitCode.INVALID_INPUT.value());
    assertThat(result.stderr()).contains("safe slug").doesNotContain("Exception");
  }

  @Test
  void reportLast_whenStateRootIsSymlink_rejectsWithoutReadingTarget() throws Exception {
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      Path outsideRoot = tempDir.resolve("outside-state");
      Path stateRoot = tempDir.resolve(".local/share/fluxion");
      Files.createDirectories(outsideRoot);
      Files.createDirectories(stateRoot.getParent());
      Path outsideState = outsideRoot.resolve("default.state.json");
      Files.writeString(
          outsideState,
          """
          {
            "schemaVersion": 2,
            "profileName": "default",
            "entries": [],
            "phaseEntries": []
          }
          """);
      String original = Files.readString(outsideState);
      createSymlinkOrSkip(stateRoot, outsideRoot);

      CliResult result = execute("report", "last", "--profile", "default");

      assertThat(result.exitCode()).isEqualTo(ExitCode.IO_ERROR.value());
      assertThat(result.stderr()).contains("Failed to read state file");
      assertThat(outsideState).hasContent(original);
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

  @Test
  void doctor_whenCompiledBinaryArchiveUnsupported_returnsDependencyError() throws Exception {
    Path config = writeUnsupportedBinaryArchiveConfig();

    CliResult result = execute("doctor", "--skip-network", "-c", config.toString());

    assertThat(result.exitCode()).isEqualTo(ExitCode.EXTERNAL_DEPENDENCY_ERROR.value());
    assertThat(result.stdout()).contains("[fail] binary artifact").contains("rg.7z");
    assertThat(result.stderr()).contains("Doctor found");
  }

  @Test
  void doctor_whenChecksumUrlConfigured_checksChecksumNetwork() throws Exception {
    Path config = writeBinaryWithChecksumUrlConfig();
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult result = execute("doctor", "--skip-network", "-c", config.toString());

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("[warn] network")
          .contains("rg.tar.gz")
          .contains("[warn] checksum network")
          .contains("rg.sha256");
      assertThat(result.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
  }

  @Test
  void doctor_whenSignatureUrlConfigured_checksGpgAndSignatureNetwork() throws Exception {
    Path config = writeBinaryWithSignatureUrlConfig();
    String originalHome = System.getProperty("user.home");
    System.setProperty("user.home", tempDir.toString());
    try {
      CliResult result = execute("doctor", "--skip-network", "-c", config.toString());

      assertThat(result.exitCode()).isEqualTo(ExitCode.SUCCESS.value());
      assertThat(result.stdout())
          .contains("[pass] signature command")
          .contains("gpg")
          .contains("[warn] signature network")
          .contains("rg.tar.gz.asc");
      assertThat(result.stderr()).isEmpty();
    } finally {
      System.setProperty("user.home", originalHome);
    }
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

  private void createSymlinkOrSkip(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      Assumptions.abort("Symbolic links are not supported: " + e.getMessage());
    }
  }

  private List<String> directoryLayout(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      return paths.map(root::relativize).map(Path::toString).sorted().toList();
    }
  }

  private void assertWorkstationSelection(String output, String operation) {
    assertThat(output)
        .contains("Operation: " + operation)
        .contains("Manifest/Profile: workstation-skip-test")
        .contains("Host: os=linux")
        .contains("State: ")
        .contains("Selected WorkstationProfile entries:")
        .contains("selected type=packages items=git")
        .contains("Skipped WorkstationProfile entries:")
        .contains("arch-only type=dnf-packages")
        .contains("Planned counts: source_setups=0 selected=1 skipped=1 items=1");
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

  private Path writeDuplicatePackageConfig() throws IOException {
    Path config = tempDir.resolve("duplicate-package-profile.yaml");
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
                packages: [git, git]
        """);
    return config;
  }

  private Path writeDryRunDefaultConfig(Path mutation) throws IOException {
    Path config = tempDir.resolve("dry-run-default.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: dry-run-default-test
        spec:
          policy:
            dryRun: true
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: mutation-sentinel
              kind: commands
              spec:
                commands:
                  - ["/bin/sh", "-c", "touch %s"]
        """
            .formatted(mutation));
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

  private Path writeFailingShellConfig() throws IOException {
    Path config = tempDir.resolve("failing-shell-profile.yaml");
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
                name: fail
                commands: ["exit 17"]
        """);
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

  private Path writeMultiPhaseResumeConfig(Path baseMarker, Path desktopMarker) throws IOException {
    Path config = tempDir.resolve("multi-phase-resume.yaml");
    Files.writeString(
        config,
        """
        profile: multi-phase-resume
        os:
          type: fedora
          release: "44"
        jobs:
          - name: base
            restartPolicy:
              type: prompt-logout
              message: Continue in a fresh session.
            steps:
              - type: shell-command
                name: base-marker
                commands:
                  - "touch %s"
          - name: desktop
            dependsOn: [base]
            steps:
              - type: shell-command
                name: desktop-marker
                commands:
                  - "touch %s"
        """
            .formatted(baseMarker, desktopMarker));
    return config;
  }

  private Path writeReprobeConfig(Path beforeMarker, Path afterMarker) throws IOException {
    Path config = tempDir.resolve("reprobe-profile.yaml");
    Files.writeString(
        config,
        """
        profile: reprobe-profile
        os:
          type: fedora
          release: "44"
        jobs:
          - name: base
            steps:
              - type: shell-command
                name: before
                commands:
                  - "touch %s"
              - type: shell-command
                name: after
                commands:
                  - "touch %s"
        """
            .formatted(beforeMarker, afterMarker));
    return config;
  }

  private Path writeGuardedCommandConfig(Path marker) throws IOException {
    Path config = tempDir.resolve("guarded-command.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: guarded-command-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: guarded
              kind: commands
              spec:
                commands:
                  - name: guarded-command
                    argv: ["/bin/sh", "-c", "touch %s"]
                    confirm: "Create the marker?"
        """
            .formatted(marker));
    return config;
  }

  private Path writeWorkstationProfileWithSkippedPlan() throws IOException {
    Path config = tempDir.resolve("workstation-skipped.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: workstation-skip-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: selected
              kind: dnf-packages
              spec:
                packages: [git]
            - name: arch-only
              kind: dnf-packages
              when:
                distribution: no-such-os
              spec:
                packages: [curl]
        """);
    return config;
  }

  private Path writeWorkstationProfileWithSensitiveCommand() throws IOException {
    Path config = tempDir.resolve("workstation-sensitive.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: workstation-sensitive-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: sensitive-command
              kind: commands
              spec:
                commands:
                  - ["/bin/bash", "-lc", "printf 'token=super-secret-token' >&2; exit 7"]
        """);
    return config;
  }

  private Path writeWorkstationProfileWithSignedScriptUrl() throws IOException {
    Path config = tempDir.resolve("workstation-signed-script.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: workstation-signed-script
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: signed-script
              kind: shell-scripts
              spec:
                scripts:
                  - name: installer
                    url: "https://example.test/install.sh?X-Amz-Signature=sensitive-signature#sensitive-fragment"
                    sha256: "0000000000000000000000000000000000000000000000000000000000000000"
        """);
    return config;
  }

  private Path writeWorkstationProfileWithDnfSource() throws IOException {
    Path config = tempDir.resolve("workstation-source.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: workstation-source-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          sources:
            dnf:
              - name: docker
                spec:
                  id: docker
                  baseUrl: https://download.docker.com/linux/fedora/$releasever/stable
                  enabled: false
                  gpgCheck: false
          plan:
            - name: tools
              kind: dnf-packages
              spec:
                packages: [git]
        """);
    return config;
  }

  private Path writeWorkstationProfileWithInterrupt(String resumeFrom) throws IOException {
    return writeWorkstationProfileWithInterrupt(resumeFrom, 75);
  }

  private Path writeWorkstationProfileWithInterrupt(String resumeFrom, int exitCode)
      throws IOException {
    Path config = tempDir.resolve("workstation-interrupt-" + resumeFrom + ".yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: workstation-interrupt-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: pause-login
              kind: interrupt
              spec:
                message: Log out before continuing.
                instructions:
                  - Run fluxion apply again.
                resumeFrom: %s
                exitCode: %d
            - name: after-pause
              kind: commands
              spec:
                commands:
                  - ["echo", "after"]
        """
            .formatted(resumeFrom, exitCode));
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
                archivePath: rg
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
                archivePath: rg
                checksum:
                  algorithm: sha256
                  value: abcdef
        """);
    return config;
  }

  private Path writeUnsupportedBinaryArchiveConfig() throws IOException {
    Path config = tempDir.resolve("unsupported-binary-profile.yaml");
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
                url: https://example.test/rg.7z
                installPath: /usr/local/bin/rg
        """);
    return config;
  }

  private Path writeBinaryWithChecksumUrlConfig() throws IOException {
    Path config = tempDir.resolve("binary-checksum-url-profile.yaml");
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
                checksumUrl: https://example.test/rg.sha256
                signatureUrl: https://example.test/rg.tar.gz.asc
                allowedSignerFingerprint: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
                installPath: /usr/local/bin/rg
                archivePath: rg
        """);
    return config;
  }

  private Path writeBinaryWithUnboundChecksumUrlConfig() throws IOException {
    Path config = tempDir.resolve("binary-unbound-checksum-url-profile.yaml");
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
                checksumUrl: https://example.test/rg.sha256
                installPath: /usr/local/bin/rg
                archivePath: rg
        """);
    return config;
  }

  private Path writeBinaryWithSignatureUrlConfig() throws IOException {
    Path config = tempDir.resolve("binary-signature-url-profile.yaml");
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
                signatureUrl: https://example.test/rg.tar.gz.asc
                installPath: /usr/local/bin/rg
                archivePath: rg
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

  private BootstrapState stateWithSharedItemKey() {
    StateEntry core =
        new StateEntry(
            "default",
            "core",
            "shared",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty());
    StateEntry desktop =
        new StateEntry(
            "default",
            "desktop",
            "shared",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty());
    return BootstrapState.empty("default", "1.0.0").withEntry(core).withEntry(desktop);
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
