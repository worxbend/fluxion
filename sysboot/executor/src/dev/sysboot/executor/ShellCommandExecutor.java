package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.Map;

public final class ShellCommandExecutor {

  private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(30);

  private final ShellRunner shellRunner;

  public ShellCommandExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(ShellCommandModule module) {
    boolean failed = false;
    for (String command : module.commands()) {
      ProcessResult result = runCommand(module, command);
      if (!result.isSuccess() && !module.continueOnError()) {
        return failure(module.name().value(), result);
      }
      failed = failed || !result.isSuccess();
    }
    return failed
        ? new StepResult.Failure(
            module.name().value(), "One or more shell commands failed", 1, Duration.ZERO)
        : new StepResult.Success(module.name().value(), Duration.ZERO);
  }

  private ProcessResult runCommand(ShellCommandModule module, String command) {
    var env = module.workingDir().map(path -> Map.of("PWD", path.toString())).orElse(Map.of());
    return shellRunner.run(
        java.util.List.of(module.shell(), "-lc", command), env, COMMAND_TIMEOUT);
  }

  private StepResult failure(String item, ProcessResult result) {
    return new StepResult.Failure(
        item, result.stdout() + result.stderr(), result.exitCode(), result.elapsed());
  }
}
