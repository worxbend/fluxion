package dev.sysboot.executor;

import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DefaultShellExecutor {

  private static final Duration CHSH_TIMEOUT = Duration.ofSeconds(30);
  private static final String ITEM = "default-shell";

  private final ShellRunner shellRunner;
  private final Optional<String> configuredTargetUser;

  public DefaultShellExecutor(ShellRunner shellRunner) {
    this(shellRunner, Optional.empty());
  }

  DefaultShellExecutor(ShellRunner shellRunner, Optional<String> configuredTargetUser) {
    this.shellRunner = shellRunner;
    this.configuredTargetUser = configuredTargetUser;
  }

  public StepResult execute(DefaultShellModule module) {
    if (!module.shellPath().isAbsolute()) {
      return new StepResult.Failure(
          ITEM, "Shell path must be absolute: " + module.shellPath(), 1, Duration.ZERO);
    }
    if (!Files.isExecutable(module.shellPath())) {
      return new StepResult.Failure(
          ITEM, "Shell binary not found or executable: " + module.shellPath(), 1, Duration.ZERO);
    }

    var result = shellRunner.run(commandPreview(module), Map.of(), CHSH_TIMEOUT);

    return result.exitCode() == 0
        ? new StepResult.Success(ITEM, result.elapsed())
        : new StepResult.Failure(ITEM, result.stderr(), result.exitCode(), result.elapsed());
  }

  public List<String> commandPreview(DefaultShellModule module) {
    if (!module.shellPath().isAbsolute()) {
      throw new IllegalArgumentException("Shell path must be absolute: " + module.shellPath());
    }
    String targetUser = TargetUserResolver.resolve(configuredTargetUser);
    return List.of("sudo", "chsh", "-s", module.shellPath().toString(), targetUser);
  }
}
