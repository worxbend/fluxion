package dev.sysboot.executor;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;

/**
 * The shape every multi-item executor shares: run some commands, collect what failed, decide
 * whether that is a failure.
 *
 * <p>Eight executors had a byte-identical {@code detail(ProcessResult)} and nine repeated the same
 * "accumulate failures, then honour continueOnError" ending. Both now live here, so the rule for
 * turning a process result into a user-facing message is defined once rather than drifting eight
 * ways.
 */
final class StepOutcome {

  private StepOutcome() {}

  /**
   * The most useful text a failed process produced.
   *
   * <p>stderr first, because that is where tools put the reason; stdout when stderr is empty,
   * because plenty of tools report failures there; the exit code when both are silent, because
   * "exit 3" beats an empty message.
   */
  static String detail(ProcessResult result) {
    String text = result.stderr().isBlank() ? result.stdout() : result.stderr();
    return text.isBlank() ? "exit " + result.exitCode() : text.strip();
  }

  /**
   * Success, or a failure naming every item that failed.
   *
   * <p>{@code continueOnError} means "report but do not fail the step", so the failures are still
   * collected and still visible — they just do not stop the run.
   */
  static StepResult of(ModuleName name, List<String> failures, boolean continueOnError) {
    if (failures.isEmpty() || continueOnError) {
      return new StepResult.Success(name.value(), Duration.ZERO);
    }
    return new StepResult.Failure(name.value(), String.join("; ", failures), 1, Duration.ZERO);
  }
}
