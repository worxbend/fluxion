package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class ZypperRepositoryInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;

  public ZypperRepositoryInstaller(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult add(ZypperRepositorySourceSetup setup) {
    ProcessResult result = shellRunner.run(addCommand(setup), Map.of(), INSTALL_TIMEOUT);
    if (result.isSuccess()) {
      return new StepResult.Success(setup.repoFilePath().toString(), result.elapsed());
    }
    return new StepResult.Failure(
        setup.repoFilePath().toString(),
        result.stdout() + result.stderr(),
        result.exitCode(),
        result.elapsed());
  }

  public List<String> addCommand(ZypperRepositorySourceSetup setup) {
    return List.of("/bin/bash", "-lc", script(setup));
  }

  private String script(ZypperRepositorySourceSetup setup) {
    return "printf %s "
        + shellQuote(repoFileContent(setup))
        + " | sudo tee "
        + shellQuote(setup.repoFilePath().toString())
        + " >/dev/null && sudo zypper refresh";
  }

  private String repoFileContent(ZypperRepositorySourceSetup setup) {
    var builder = new StringBuilder();
    builder.append('[').append(setup.repositoryId()).append("]\n");
    builder.append("name=").append(setup.repositoryId()).append('\n');
    builder.append("baseurl=").append(setup.baseUrl()).append('\n');
    builder.append("enabled=").append(setup.enabled() ? "1" : "0").append('\n');
    builder.append("gpgcheck=").append(setup.gpgCheck() ? "1" : "0").append('\n');
    setup.gpgKeyUrl().ifPresent(url -> builder.append("gpgkey=").append(url).append('\n'));
    return builder.toString();
  }

  private String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
