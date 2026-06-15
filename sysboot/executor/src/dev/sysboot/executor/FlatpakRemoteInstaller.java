package dev.sysboot.executor;

import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FlatpakRemoteInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;

  public FlatpakRemoteInstaller(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult add(FlatpakRemoteModule module) {
    ProcessResult result = shellRunner.run(addCommand(module), Map.of(), INSTALL_TIMEOUT);
    if (result.isSuccess()) {
      return new StepResult.Success(module.remote(), result.elapsed());
    }
    return new StepResult.Failure(
        module.remote(), result.stdout() + result.stderr(), result.exitCode(), result.elapsed());
  }

  public List<String> addCommand(FlatpakRemoteModule module) {
    var command = new ArrayList<String>();
    command.add("flatpak");
    if (!module.system()) {
      command.add("--user");
    }
    command.add("remote-add");
    command.add("--if-not-exists");
    command.add(module.remote());
    command.add(module.url().toString());
    return List.copyOf(command);
  }
}
