package dev.sysboot.executor;

import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class DefaultShellExecutor {

  private static final Duration CHSH_TIMEOUT = Duration.ofSeconds(30);
  private static final String ITEM = "default-shell";

  private final ShellRunner shellRunner;

  public DefaultShellExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(DefaultShellModule module) {
    if (!Files.exists(module.shellPath())) {
      return new StepResult.Failure(
          ITEM, "Shell binary not found: " + module.shellPath(), 1, Duration.ZERO);
    }

    var result =
        shellRunner.run(
            List.of("chsh", "-s", module.shellPath().toString()), Map.of(), CHSH_TIMEOUT);

    return result.exitCode() == 0
        ? new StepResult.Success(ITEM, result.elapsed())
        : new StepResult.Failure(ITEM, result.stderr(), result.exitCode(), result.elapsed());
  }
}
