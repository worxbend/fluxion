package dev.sysboot.executor;

import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ToolSpec;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Applies a binstaller {@code BinaryDistributionProfile}.
 *
 * <p>The two tools line up verb for verb, so the mapping is direct rather than a translation:
 *
 * <table>
 *   <caption>Verb mapping</caption>
 *   <tr><th>Fluxion</th><th>binstaller</th></tr>
 *   <tr><td>{@code plan}, {@code dry-run}</td><td>{@code plan}</td></tr>
 *   <tr><td>{@code apply}</td><td>{@code apply}</td></tr>
 *   <tr><td>{@code status}, {@code diff}</td><td>{@code versions}</td></tr>
 * </table>
 *
 * <p>Fluxion never runs {@code apply} for a preview, so a dry run cannot touch the machine.
 */
public final class BinstallerExecutor {

  private static final Duration APPLY_TIMEOUT = Duration.ofMinutes(30);
  private static final Duration READ_ONLY_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;
  private final ToolResolver toolResolver;

  public BinstallerExecutor(ShellRunner shellRunner) {
    this(shellRunner, new BrokeredToolResolver(new ToolBroker()));
  }

  BinstallerExecutor(ShellRunner shellRunner, ToolResolver toolResolver) {
    this.shellRunner = shellRunner;
    this.toolResolver = toolResolver;
  }

  public StepResult execute(BinstallerModule module) {
    try {
      List<String> command = applyCommand(binary(module), module);
      ProcessResult result = shellRunner.run(command, Map.of(), APPLY_TIMEOUT);
      return result.isSuccess()
          ? new StepResult.Success(module.itemKey(), result.elapsed())
          : new StepResult.Failure(
              module.itemKey(), failureMessage(result), result.exitCode(), result.elapsed());
    } catch (ToolResolutionException | IllegalArgumentException e) {
      return new StepResult.Failure(
          module.itemKey(), "Failed to prepare binstaller: " + e.getMessage(), 1, Duration.ZERO);
    }
  }

  /**
   * Runs {@code binstaller plan} so {@code fluxion plan} shows the tools and versions that would be
   * installed rather than an opaque command line.
   */
  public Optional<String> plan(BinstallerModule module) {
    try {
      ProcessResult result =
          shellRunner.run(planCommand(binary(module), module), Map.of(), READ_ONLY_TIMEOUT);
      return result.isSuccess() ? Optional.of(result.stdout()) : Optional.empty();
    } catch (ToolResolutionException | ShellExecutionException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Runs {@code binstaller versions} for drift reporting. */
  public Optional<String> versions(BinstallerModule module) {
    try {
      List<String> command = new ArrayList<>(List.of(binary(module), "versions"));
      appendConfig(command, module);
      appendSelection(command, module);
      ProcessResult result = shellRunner.run(List.copyOf(command), Map.of(), READ_ONLY_TIMEOUT);
      return result.isSuccess() ? Optional.of(result.stdout()) : Optional.empty();
    } catch (ToolResolutionException | ShellExecutionException | IllegalArgumentException e) {
      return Optional.empty();
    }
  }

  /** Command preview used by {@code plan} and {@code dry-run}. */
  public List<String> commandPreview(BinstallerModule module) {
    return planCommand(module.binstallerBinary(), module);
  }

  private List<String> planCommand(String binary, BinstallerModule module) {
    var command = new ArrayList<>(List.of(binary, "plan"));
    appendConfig(command, module);
    appendSelection(command, module);
    return List.copyOf(command);
  }

  private List<String> applyCommand(String binary, BinstallerModule module) {
    var command = new ArrayList<>(List.of(binary, "apply"));
    appendConfig(command, module);
    appendSelection(command, module);
    if (module.locked()) {
      command.add("--locked");
      module.lockFile().ifPresent(path -> command.addAll(List.of("--lock-file", path.toString())));
    }
    return List.copyOf(command);
  }

  private void appendConfig(List<String> command, BinstallerModule module) {
    command.addAll(List.of("--config", module.config().toString()));
  }

  private void appendSelection(List<String> command, BinstallerModule module) {
    module.only().forEach(tool -> command.addAll(List.of("--only", tool)));
    module.skip().forEach(tool -> command.addAll(List.of("--skip", tool)));
  }

  private String binary(BinstallerModule module) {
    return toolResolver.resolve(toolSpec(module)).toString();
  }

  static ToolSpec toolSpec(BinstallerModule module) {
    ToolSpec base = KnownTools.BINSTALLER;
    return base.withVersion(module.installerVersion()).withBinaryName(module.binstallerBinary());
  }

  private String failureMessage(ProcessResult result) {
    String detail = result.stderr().isBlank() ? result.stdout() : result.stderr();
    return detail.isBlank()
        ? "binstaller exited with code " + result.exitCode()
        : "binstaller exited with code " + result.exitCode() + ": " + detail.strip();
  }

  /** Path a lock file would be written to, for reporting. */
  public static Optional<Path> lockFile(BinstallerModule module) {
    return module.lockFile();
  }
}
