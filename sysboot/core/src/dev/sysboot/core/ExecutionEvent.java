package dev.sysboot.core;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ExecutionEvent(
    ModuleName moduleName,
    String item,
    EventKind kind,
    Optional<StepResult> result,
    Instant timestamp,
    Optional<String> phaseContext) {

  public ExecutionEvent {
    Objects.requireNonNull(moduleName);
    Objects.requireNonNull(item);
    Objects.requireNonNull(kind);
    Objects.requireNonNull(result);
    Objects.requireNonNull(timestamp);
    Objects.requireNonNull(phaseContext);
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

  public static ExecutionEvent error(ModuleName module, String item, StepResult result) {
    Objects.requireNonNull(result);
    return new ExecutionEvent(
        module, item, EventKind.ERROR, Optional.of(result), Instant.now(), Optional.empty());
  }
}
