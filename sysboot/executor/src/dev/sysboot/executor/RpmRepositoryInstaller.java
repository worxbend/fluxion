package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class RpmRepositoryInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;

  public RpmRepositoryInstaller(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult add(RpmRepositoryModule module) {
    ProcessResult result = shellRunner.run(addCommand(module), Map.of(), INSTALL_TIMEOUT);
    if (result.isSuccess()) {
      return new StepResult.Success(module.repoFilePath().toString(), result.elapsed());
    }
    return new StepResult.Failure(
        module.repoFilePath().toString(),
        result.stdout() + result.stderr(),
        result.exitCode(),
        result.elapsed());
  }

  public List<String> addCommand(RpmRepositoryModule module) {
    return List.of("/bin/bash", "-lc", script(module));
  }

  private String script(RpmRepositoryModule module) {
    return "printf %s "
        + shellQuote(repoFileContent(module))
        + " | sudo tee "
        + shellQuote(module.repoFilePath().toString())
        + " >/dev/null && sudo dnf makecache --refresh";
  }

  private String repoFileContent(RpmRepositoryModule module) {
    var builder = new StringBuilder();
    builder.append('[').append(module.repositoryId()).append("]\n");
    builder.append("name=").append(module.repositoryId()).append('\n');
    builder.append("baseurl=").append(module.baseUrl()).append('\n');
    builder.append("enabled=").append(module.enabled() ? "1" : "0").append('\n');
    builder.append("gpgcheck=").append(module.gpgCheck() ? "1" : "0").append('\n');
    module.gpgKeyUrl().ifPresent(url -> builder.append("gpgkey=").append(url).append('\n'));
    return builder.toString();
  }

  private String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
