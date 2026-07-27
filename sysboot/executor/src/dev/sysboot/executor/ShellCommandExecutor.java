package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellCommandItem;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ShellCommandExecutor {

  private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);

  private final ShellRunner shellRunner;
  private final SensitiveTextRedactor redactor;

  public ShellCommandExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
    this.redactor = new SensitiveTextRedactor();
  }

  public StepResult execute(ShellCommandModule module) {
    var failures = new ArrayList<String>();
    for (ShellCommandItem item : module.items()) {
      StepResult skipped = skipResult(item);
      if (skipped != null) {
        continue;
      }
      ProcessResult result = runCommand(item);
      if (!item.allowsExitCode(result.exitCode()) && !module.continueOnError()) {
        return failure(item.name(), item, result);
      }
      if (!item.allowsExitCode(result.exitCode())) {
        failures.add(describeFailure(item, result));
      }
    }
    return failures.isEmpty()
        ? new StepResult.Success(module.name().value(), Duration.ZERO)
        : new StepResult.Failure(
            module.name().value(), String.join("; ", failures), 1, Duration.ZERO);
  }

  /**
   * Names the command that failed and its exit code.
   *
   * <p>"One or more shell commands failed" told the user nothing about which of several commands
   * broke. The command text is redacted, so a token in an inline command never reaches the report.
   */
  private String describeFailure(ShellCommandItem item, ProcessResult result) {
    return "%s exited %d: %s"
        .formatted(
            item.name(),
            result.exitCode(),
            String.join(" ", redactor.redactCommand(item.commandPreview(), item.environment())));
  }

  public List<String> commandPreview(ShellCommandItem item) {
    return redactor.redactCommand(item.commandPreview(), item.environment());
  }

  private StepResult skipResult(ShellCommandItem item) {
    if (item.creates().filter(Files::exists).isPresent()) {
      return new StepResult.Skipped(item.name(), "creates path already exists");
    }
    return null;
  }

  private ProcessResult runCommand(ShellCommandItem item) {
    if (unlessMatches(item)) {
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
    return shellRunner.run(item.command(), environment(item), item.timeout());
  }

  private boolean unlessMatches(ShellCommandItem item) {
    return item.unless()
        .map(
            command ->
                shellRunner
                    .run(
                        java.util.List.of(item.shell(), "-lc", command),
                        environment(item),
                        CHECK_TIMEOUT)
                    .isSuccess())
        .orElse(false);
  }

  private Map<String, String> environment(ShellCommandItem item) {
    Map<String, String> values =
        item.environment().stream()
            .collect(Collectors.toMap(variable -> variable.name(), variable -> variable.value()));
    item.workingDir().ifPresent(path -> values.put("PWD", path.toString()));
    return values;
  }

  private StepResult failure(String item, ShellCommandItem commandItem, ProcessResult result) {
    return new StepResult.Failure(
        item,
        redactor.redact(result.stdout() + result.stderr(), commandItem.environment()),
        result.exitCode(),
        result.elapsed());
  }
}
