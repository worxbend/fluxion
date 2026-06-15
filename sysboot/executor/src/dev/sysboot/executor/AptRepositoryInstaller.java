package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class AptRepositoryInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;

  public AptRepositoryInstaller(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult add(AptRepositoryModule module) {
    ProcessResult result = shellRunner.run(addCommand(module), Map.of(), INSTALL_TIMEOUT);
    if (result.isSuccess()) {
      return new StepResult.Success(module.sourceListPath().toString(), result.elapsed());
    }
    return new StepResult.Failure(
        module.sourceListPath().toString(),
        result.stdout() + result.stderr(),
        result.exitCode(),
        result.elapsed());
  }

  public List<String> addCommand(AptRepositoryModule module) {
    return List.of("/bin/bash", "-lc", script(module));
  }

  private String script(AptRepositoryModule module) {
    String writeSource =
        "printf %s\\\\n "
            + shellQuote(module.sourceEntry())
            + " | sudo tee "
            + shellQuote(module.sourceListPath().toString())
            + " >/dev/null";
    return module
        .signingKeyUrl()
        .map(url -> keyInstallCommand(module) + " && " + writeSource + " && sudo apt-get update")
        .orElse(writeSource + " && sudo apt-get update");
  }

  private String keyInstallCommand(AptRepositoryModule module) {
    String keyring = module.keyringPath().orElseThrow().toString();
    return "sudo install -d -m 0755 "
        + shellQuote(module.keyringPath().orElseThrow().getParent().toString())
        + " && curl -fsSL "
        + shellQuote(module.signingKeyUrl().orElseThrow().toString())
        + " | sudo gpg --dearmor -o "
        + shellQuote(keyring);
  }

  private String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
