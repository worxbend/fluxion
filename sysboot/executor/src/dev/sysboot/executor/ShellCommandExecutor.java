package dev.sysboot.executor;

import dev.sysboot.core.ExecutionApproval;
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
import java.util.Optional;
import java.util.stream.Collectors;

public final class ShellCommandExecutor {

  private static final Duration CHECK_TIMEOUT = Duration.ofSeconds(30);

  private final ShellRunner shellRunner;
  private final SensitiveTextRedactor redactor;
  private final ExecutionApproval approval;

  public ShellCommandExecutor(ShellRunner shellRunner) {
    this(shellRunner, ExecutionApproval.denyAll());
  }

  public ShellCommandExecutor(ShellRunner shellRunner, ExecutionApproval approval) {
    this.shellRunner = shellRunner;
    this.approval = approval;
    this.redactor = new SensitiveTextRedactor();
  }

  public StepResult execute(ShellCommandModule module) {
    var failures = new ArrayList<String>();
    for (ShellCommandItem item : module.items()) {
      StepResult itemResult = executeItem(item);
      if (itemResult instanceof StepResult.Skipped) {
        continue;
      }
      if (itemResult instanceof StepResult.Failure failure && !module.continueOnError()) {
        return failure;
      }
      if (itemResult instanceof StepResult.Failure failure) {
        failures.add(failure.errorMessage());
      }
    }
    return failures.isEmpty()
        ? new StepResult.Success(module.name().value(), Duration.ZERO)
        : new StepResult.Failure(
            module.name().value(), String.join("; ", failures), 1, Duration.ZERO);
  }

  StepResult executeItem(ShellCommandItem item) {
    return executeItem(item, approval);
  }

  StepResult executeItem(ShellCommandItem item, ExecutionApproval executionApproval) {
    Optional<StepResult> confirmationFailure = confirmationFailure(item, executionApproval);
    if (confirmationFailure.isPresent()) {
      return confirmationFailure.orElseThrow();
    }
    if (item.creates().filter(Files::exists).isPresent()) {
      return new StepResult.Skipped(item.name(), "creates path already exists");
    }
    if (unlessMatches(item)) {
      return new StepResult.Skipped(item.name(), "unless guard matched");
    }
    ProcessResult result =
        withSensitiveOutput(
            item,
            () ->
                shellRunner.run(
                    item.command(), environment(item), item.workingDir(), item.timeout()));
    if (item.allowsExitCode(result.exitCode())) {
      return new StepResult.Success(item.name(), result.elapsed());
    }
    return failure(item.name(), item, result);
  }

  private Optional<StepResult> confirmationFailure(
      ShellCommandItem item, ExecutionApproval executionApproval) {
    if (item.confirm().isEmpty()) {
      return Optional.empty();
    }
    var request =
        new ExecutionApproval.ConfirmationRequest(item.name(), item.confirm().orElseThrow());
    if (executionApproval.approve(request)) {
      return Optional.empty();
    }
    return Optional.of(
        new StepResult.Failure(
            item.name(),
            "Explicit confirmation required; rerun apply with --yes",
            2,
            Duration.ZERO));
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

  private boolean unlessMatches(ShellCommandItem item) {
    return item.unless()
        .map(
            command ->
                withSensitiveOutput(
                    item,
                    () ->
                        shellRunner
                            .run(
                                java.util.List.of(item.shell(), "-lc", command),
                                environment(item),
                                item.workingDir(),
                                CHECK_TIMEOUT)
                            .isSuccess()))
        .orElse(false);
  }

  private <T> T withSensitiveOutput(ShellCommandItem item, java.util.function.Supplier<T> action) {
    return ExecutionOutput.withSensitiveEnvironment(item.environment(), action);
  }

  private Map<String, String> environment(ShellCommandItem item) {
    return item.environment().stream()
        .collect(Collectors.toMap(variable -> variable.name(), variable -> variable.value()));
  }

  private StepResult failure(String item, ShellCommandItem commandItem, ProcessResult result) {
    String output = redactor.redact(result.stdout() + result.stderr(), commandItem.environment());
    String description = describeFailure(commandItem, result);
    String detail = output.isBlank() ? description : description + ": " + output;
    return new StepResult.Failure(item, detail, result.exitCode(), result.elapsed());
  }
}
