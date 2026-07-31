package dev.sysboot.executor;

import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Applies {@code git config} entries, one key at a time so each can be probed and reported. */
public final class GitConfigExecutor {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);

  private final ShellRunner shellRunner;

  public GitConfigExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(GitConfigModule module) {
    var failures = new ArrayList<String>();
    for (String key : module.sortedKeys()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      String desired = module.entries().get(key);
      if (desired.equals(currentValue(module, key).orElse(null))) {
        continue;
      }
      ProcessResult result = shellRunner.run(setCommand(module, key, desired), Map.of(), TIMEOUT);
      if (!result.isSuccess()) {
        failures.add(key + ": " + StepOutcome.detail(result));
      }
    }
    return StepOutcome.of(module.name(), failures, module.continueOnError());
  }

  StepResult executeItem(GitConfigModule module, String key) {
    String itemKey = module.itemKey(key);
    String desired = module.entries().get(key);
    if (desired.equals(currentValue(module, key).orElse(null))) {
      return new StepResult.Success(itemKey, Duration.ZERO);
    }
    ProcessResult result = shellRunner.run(setCommand(module, key, desired), Map.of(), TIMEOUT);
    return result.isSuccess()
        ? new StepResult.Success(itemKey, result.elapsed())
        : new StepResult.Failure(
            itemKey, StepOutcome.detail(result), result.exitCode(), result.elapsed());
  }

  /** Current value of a key, empty when unset. Used for skip decisions and drift reporting. */
  public Optional<String> currentValue(GitConfigModule module, String key) {
    ProcessResult result =
        shellRunner.run(
            privileged(module, List.of("git", "config", module.scope().flag(), "--get", key)),
            Map.of(),
            TIMEOUT);
    // git config --get exits 1 when the key is simply absent, which is not an error here.
    return result.isSuccess() && !result.stdout().isBlank()
        ? Optional.of(result.stdout().strip())
        : Optional.empty();
  }

  public List<String> commandPreview(GitConfigModule module) {
    var preview = new ArrayList<String>();
    module
        .sortedKeys()
        .forEach(key -> preview.addAll(setCommand(module, key, module.entries().get(key))));
    return List.copyOf(preview);
  }

  List<String> commandPreview(GitConfigModule module, String key) {
    return setCommand(module, key, module.entries().get(key));
  }

  private List<String> setCommand(GitConfigModule module, String key, String value) {
    return privileged(module, List.of("git", "config", module.scope().flag(), key, value));
  }

  private List<String> privileged(GitConfigModule module, List<String> command) {
    if (!module.scope().privileged()) {
      return command;
    }
    var elevated = new ArrayList<String>(command.size() + 1);
    elevated.add("sudo");
    elevated.addAll(command);
    return List.copyOf(elevated);
  }
}
