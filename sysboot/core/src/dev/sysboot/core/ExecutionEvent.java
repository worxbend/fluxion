package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Something that happened during a run.
 *
 * @param outputLine a single line of live command output, present only on {@link
 *     EventKind#ITEM_OUTPUT}
 */
public record ExecutionEvent(
    ModuleName moduleName,
    String item,
    EventKind kind,
    Optional<StepResult> result,
    Instant timestamp,
    Optional<String> phaseContext,
    Optional<String> outputLine) {

  public ExecutionEvent {
    Objects.requireNonNull(moduleName);
    Objects.requireNonNull(item);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(result);
    Objects.requireNonNull(timestamp);
    Objects.requireNonNull(phaseContext);
    Objects.requireNonNull(outputLine);
  }

  public ExecutionEvent(
      ModuleName moduleName,
      String item,
      EventKind kind,
      Optional<StepResult> result,
      Instant timestamp,
      Optional<String> phaseContext) {
    this(moduleName, item, kind, result, timestamp, phaseContext, Optional.empty());
  }

  public static ExecutionEvent phaseStarted(PhaseName phase) {
    return new ExecutionEvent(
        new ModuleName(phase.value()),
        "",
        EventKind.PHASE_STARTED,
        Optional.empty(),
        Instant.now(),
        Optional.of(phase.value()));
  }

  public static ExecutionEvent phaseCompleted(PhaseName phase) {
    return new ExecutionEvent(
        new ModuleName(phase.value()),
        "",
        EventKind.PHASE_COMPLETED,
        Optional.empty(),
        Instant.now(),
        Optional.of(phase.value()));
  }

  public static ExecutionEvent phaseFailed(PhaseName phase) {
    return new ExecutionEvent(
        new ModuleName(phase.value()),
        "",
        EventKind.PHASE_FAILED,
        Optional.empty(),
        Instant.now(),
        Optional.of(phase.value()));
  }

  public static ExecutionEvent phaseBlocked(PhaseName phase, String blockedBy) {
    return new ExecutionEvent(
        new ModuleName(phase.value()),
        blockedBy,
        EventKind.PHASE_BLOCKED,
        Optional.empty(),
        Instant.now(),
        Optional.of(phase.value()));
  }

  public static ExecutionEvent restartRequired(PhaseName phase, String message) {
    return new ExecutionEvent(
        new ModuleName(phase.value()),
        message,
        EventKind.RESTART_REQUIRED,
        Optional.empty(),
        Instant.now(),
        Optional.of(phase.value()));
  }

  public static ExecutionEvent moduleStarted(ModuleName module) {
    return new ExecutionEvent(
        module, "", EventKind.MODULE_STARTED, Optional.empty(), Instant.now(), Optional.empty());
  }

  public static ExecutionEvent moduleCompleted(ModuleName module) {
    return new ExecutionEvent(
        module, "", EventKind.MODULE_COMPLETED, Optional.empty(), Instant.now(), Optional.empty());
  }

  public static ExecutionEvent itemStarted(ModuleName module, String item) {
    return new ExecutionEvent(
        module, item, EventKind.ITEM_STARTED, Optional.empty(), Instant.now(), Optional.empty());
  }

  /**
   * One line of live output from the command running for {@code item}.
   *
   * <p>Emitted while the command is still running, so a ten-minute package upgrade shows progress
   * instead of nothing. The line is carried in {@code item}'s companion field rather than a {@link
   * StepResult}, because no step has completed yet.
   */
  public static ExecutionEvent itemOutput(ModuleName module, String item, String line) {
    Objects.requireNonNull(line);
    return new ExecutionEvent(
        module,
        item,
        EventKind.ITEM_OUTPUT,
        Optional.empty(),
        Instant.now(),
        Optional.empty(),
        Optional.of(line));
  }

  public static ExecutionEvent itemCompleted(ModuleName module, String item, StepResult result) {
    Objects.requireNonNull(result);
    return new ExecutionEvent(
        module,
        item,
        EventKind.ITEM_COMPLETED,
        Optional.of(result),
        Instant.now(),
        Optional.empty());
  }

  /**
   * The run stopped because the user asked it to.
   *
   * @param nextPlanEntry the entry a resume should start from, when one is known
   */
  public static ExecutionEvent cancelled(PhaseName phase, Optional<String> nextPlanEntry) {
    Objects.requireNonNull(nextPlanEntry);
    return new ExecutionEvent(
        new ModuleName(phase.value()),
        nextPlanEntry.orElse(""),
        EventKind.CANCELLED,
        Optional.empty(),
        Instant.now(),
        Optional.of(phase.value()));
  }

  public static ExecutionEvent error(ModuleName module, String item, StepResult result) {
    Objects.requireNonNull(result);
    return new ExecutionEvent(
        module, item, EventKind.ERROR, Optional.of(result), Instant.now(), Optional.empty());
  }
}
