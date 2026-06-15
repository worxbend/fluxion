package dev.sysboot.executor;

import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class PacmanRepositoryInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;

  public PacmanRepositoryInstaller(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult add(PacmanRepositoryModule module) {
    ProcessResult result = shellRunner.run(addCommand(module), Map.of(), INSTALL_TIMEOUT);
    if (result.isSuccess()) {
      return new StepResult.Success(module.repositoryName(), result.elapsed());
    }
    return new StepResult.Failure(
        module.repositoryName(),
        result.stdout() + result.stderr(),
        result.exitCode(),
        result.elapsed());
  }

  public List<String> addCommand(PacmanRepositoryModule module) {
    return List.of("/bin/bash", "-lc", script(module));
  }

  private String script(PacmanRepositoryModule module) {
    String path = shellQuote(module.configPath().toString());
    return "grep -Eq '^\\["
        + regexQuote(module.repositoryName())
        + "\\]$' "
        + path
        + " || printf %s "
        + shellQuote(repositoryBlock(module))
        + " | sudo tee -a "
        + path
        + " >/dev/null; sudo pacman -Sy";
  }

  private String repositoryBlock(PacmanRepositoryModule module) {
    var builder = new StringBuilder();
    builder.append('\n').append('[').append(module.repositoryName()).append("]\n");
    if (!module.enabled()) {
      builder.append("# ");
    }
    builder.append("Server = ").append(module.server()).append('\n');
    module.sigLevel().ifPresent(value -> builder.append("SigLevel = ").append(value).append('\n'));
    module.include().ifPresent(path -> builder.append("Include = ").append(path).append('\n'));
    return builder.toString();
  }

  private String regexQuote(String value) {
    return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
  }

  private String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
