package dev.sysboot.executor;

import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class FlatpakInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);

  private final ShellRunner shellRunner;

  public FlatpakInstaller(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult install(FlatpakModule module, String appId) {
    List<String> command = List.of("flatpak", "install", "-y", module.remote(), appId);
    ProcessResult result = shellRunner.run(command, Map.of(), INSTALL_TIMEOUT);
    if (result.isSuccess()) {
      return new StepResult.Success(appId, result.elapsed());
    }
    return new StepResult.Failure(
        appId, result.stdout() + result.stderr(), result.exitCode(), result.elapsed());
  }
}
