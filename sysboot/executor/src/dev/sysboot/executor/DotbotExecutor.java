package dev.sysboot.executor;

import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ToolSpec;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies dotfiles through <a href="https://github.com/worxbend/dotbot-go">dotbot</a>.
 *
 * <p>Dotbot already models plan, validate, dry-run, and apply. Fluxion maps its own verbs onto
 * those rather than reimplementing symlink management.
 */
public final class DotbotExecutor {

  private static final Duration DOTBOT_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;
  private final ToolResolver toolResolver;

  public DotbotExecutor(ShellRunner shellRunner) {
    this(shellRunner, new BrokeredToolResolver(new ToolBroker()));
  }

  DotbotExecutor(ShellRunner shellRunner, ToolResolver toolResolver) {
    this.shellRunner = shellRunner;
    this.toolResolver = toolResolver;
  }

  public StepResult execute(DotbotModule module) {
    try {
      var command = command(toolResolver.resolve(toolSpec(module)).toString(), module, false);
      ProcessResult result = shellRunner.run(command, Map.of(), DOTBOT_TIMEOUT);

      return result.isSuccess()
          ? new StepResult.Success(module.name().value(), result.elapsed())
          : new StepResult.Failure(
              module.name().value(),
              "dotbot exited with code " + result.exitCode() + ": " + failureDetail(result),
              result.exitCode(),
              result.elapsed());
    } catch (ToolResolutionException | IllegalArgumentException e) {
      return new StepResult.Failure(
          module.name().value(), "Failed to prepare dotbot: " + e.getMessage(), 1, Duration.ZERO);
    }
  }

  /**
   * Runs {@code dotbot plan} and returns its output, so {@code fluxion plan} shows the real
   * per-link preview instead of an opaque command string.
   */
  public Optional<String> plan(DotbotModule module) {
    try {
      var command = new java.util.ArrayList<>(planCommand(module));
      ProcessResult result = shellRunner.run(List.copyOf(command), Map.of(), DOTBOT_TIMEOUT);
      return result.isSuccess() ? Optional.of(result.stdout()) : Optional.empty();
    } catch (ToolResolutionException | ShellExecutionException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  private List<String> planCommand(DotbotModule module) {
    return List.of(
        toolResolver.resolve(toolSpec(module)).toString(),
        "plan",
        "-c",
        module.config().toString(),
        "--output",
        "json");
  }

  /** Command preview used by {@code plan} and {@code dry-run}. */
  public List<String> commandPreview(DotbotModule module) {
    return command(module.dotbotBinary(), module, true);
  }

  private List<String> command(String binary, DotbotModule module, boolean dryRun) {
    return dryRun
        ? List.of(binary, "-c", module.config().toString(), "--dry-run")
        : List.of(binary, "--config", module.config().toString());
  }

  static ToolSpec toolSpec(DotbotModule module) {
    ToolSpec base = KnownTools.DOTBOT_GO;
    return base.withVersion(module.installerVersion()).withBinaryName(module.dotbotBinary());
  }

  private String failureDetail(ProcessResult result) {
    return result.stderr().isBlank() ? result.stdout().strip() : result.stderr().strip();
  }
}
