package dev.sysboot.executor;

import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ShellReloadExecutor {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String ITEM = "shell-reload";

  private final ShellRunner shellRunner;

  public ShellReloadExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(ShellReloadModule module) {
    String binary = module.shell().binaryName();
    // --login sources /etc/profile and ~/.profile / ~/.zprofile / ~/.zshrc
    // -i makes it interactive (required for .zshrc sourcing in zsh)
    // -c "echo OK; exit 0" terminates immediately after sourcing
    var result =
        shellRunner.run(
            List.of(binary, "--login", "-i", "-c", "echo 'Shell environment OK'; exit 0"),
            Map.of(),
            TIMEOUT);

    return result.exitCode() == 0
        ? new StepResult.Success(ITEM, result.elapsed())
        : new StepResult.Failure(
            ITEM,
            "Shell init failed. Check your ." + binary + "rc for errors.\n" + result.stderr(),
            result.exitCode(),
            result.elapsed());
  }
}
