package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Fluxion delegates binary distribution to binstaller rather than reimplementing it, so what
 * matters is that each Fluxion verb maps onto the right binstaller verb — and that a preview never
 * reaches {@code apply}.
 */
class BinstallerExecutorTest {

  private static final Path BINARY = Path.of("/opt/bin/binstaller");
  private static final Path CONFIG = Path.of("/home/test/.config/binstaller/config.yaml");

  @Test
  void applyRunsBinstallerApplyWithTheProfile() {
    var runner = new RecordingShellRunner(0);
    var executor = new BinstallerExecutor(runner, spec -> BINARY);

    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands)
        .containsExactly(List.of(BINARY.toString(), "apply", "--config", CONFIG.toString()));
  }

  @Test
  void selectionFlagsArePassedThroughAndRepeatable() {
    var runner = new RecordingShellRunner(0);
    var executor = new BinstallerExecutor(runner, spec -> BINARY);

    executor.execute(
        new BinstallerModule(
            new ModuleName("binaries"),
            CONFIG,
            List.of("yazi", "neovim"),
            List.of("zig"),
            false,
            Optional.empty(),
            "v0.2.0",
            "binstaller",
            Optional.empty(),
            false));

    assertThat(runner.commands.getFirst())
        .containsSubsequence("--only", "yazi", "--only", "neovim", "--skip", "zig");
  }

  @Test
  void lockedProfilesRequireTheLockFileOnApply() {
    var runner = new RecordingShellRunner(0);
    var executor = new BinstallerExecutor(runner, spec -> BINARY);

    executor.execute(
        new BinstallerModule(
            new ModuleName("binaries"),
            CONFIG,
            List.of(),
            List.of(),
            true,
            Optional.of(Path.of("/home/test/binstaller.lock.json")),
            "v0.2.0",
            "binstaller",
            Optional.empty(),
            false));

    assertThat(runner.commands.getFirst())
        .containsSubsequence("--locked", "--lock-file", "/home/test/binstaller.lock.json");
  }

  @Test
  void planNeverRunsApply() {
    var runner = new RecordingShellRunner(0);
    var executor = new BinstallerExecutor(runner, spec -> BINARY);

    executor.plan(module());

    assertThat(runner.commands.getFirst()).contains("plan").doesNotContain("apply");
  }

  @Test
  void theDryRunPreviewIsTheRealPlanCommand() {
    var executor = new BinstallerExecutor(new RecordingShellRunner(0), spec -> BINARY);

    assertThat(executor.commandPreview(module()))
        .containsExactly("binstaller", "plan", "--config", CONFIG.toString());
  }

  @Test
  void versionsIsUsedForDriftReporting() {
    var runner = new RecordingShellRunner(0);
    var executor = new BinstallerExecutor(runner, spec -> BINARY);

    Optional<String> output = executor.versions(module());

    assertThat(output).isPresent();
    assertThat(runner.commands.getFirst()).contains("versions");
  }

  @Test
  void aNonZeroExitBecomesAFailureCarryingBinstallerOutput() {
    var runner = new RecordingShellRunner(3, "profile not found");
    var executor = new BinstallerExecutor(runner, spec -> BINARY);

    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    StepResult.Failure failure = (StepResult.Failure) result;
    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.errorMessage()).contains("profile not found");
  }

  @Test
  void anUnavailableToolFailsTheStepInsteadOfThrowing() {
    var executor =
        new BinstallerExecutor(
            new RecordingShellRunner(0),
            spec -> {
              throw new ToolResolutionException("no release for this platform");
            });

    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("Failed to prepare binstaller")
        .contains("no release for this platform");
  }

  @Test
  void theModulePinsTheBinstallerReleaseItWasConfiguredWith() {
    assertThat(BinstallerExecutor.toolSpec(module()).version()).isEqualTo("v0.2.0");
    assertThat(BinstallerExecutor.toolSpec(module()).repository()).isEqualTo("worxbend/binstaller");
  }

  private BinstallerModule module() {
    return new BinstallerModule(new ModuleName("developer-binaries"), CONFIG);
  }

  private static final class RecordingShellRunner implements ShellRunner {

    private final List<List<String>> commands = new ArrayList<>();
    private final int exitCode;
    private final String output;

    RecordingShellRunner(int exitCode) {
      this(exitCode, "");
    }

    RecordingShellRunner(int exitCode, String output) {
      this.exitCode = exitCode;
      this.output = output;
    }

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(List.copyOf(command));
      return new ProcessResult(exitCode, output, "", Duration.ofSeconds(1));
    }
  }
}
