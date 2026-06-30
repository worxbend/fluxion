package dev.sysboot.tui;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;

public final class TuiExecutionEventListener implements ExecutionEventListener {

  private static final int MAX_DRAIN_PER_TICK = 50;

  private final LinkedBlockingQueue<ExecutionEvent> eventQueue = new LinkedBlockingQueue<>();

  @Override
  public void onEvent(ExecutionEvent event) {
    eventQueue.offer(event);
  }

  public ExecutionScreenState drainInto(ExecutionScreenState state) {
    ExecutionScreenState current = state;
    int drained = 0;
    ExecutionEvent event;
    while (drained < MAX_DRAIN_PER_TICK && (event = eventQueue.poll()) != null) {
      current = applyEvent(current, event);
      drained++;
    }
    return current;
  }

  private ExecutionScreenState applyEvent(ExecutionScreenState state, ExecutionEvent event) {
    return switch (event.kind()) {
      case PHASE_STARTED -> state.withLogLine("[PHASE] " + event.moduleName().value());
      case PHASE_COMPLETED -> state.withLogLine("[PHASE DONE] " + event.moduleName().value());
      case PHASE_FAILED -> state.withLogLine("[PHASE FAILED] " + event.moduleName().value());
      case PHASE_BLOCKED -> state.withLogLine("[PHASE BLOCKED] " + event.moduleName().value());
      case RESTART_REQUIRED -> state.withLogLine("[RESTART REQUIRED] " + event.item());
      case MODULE_STARTED ->
          state
              .withModule(event.moduleName().value())
              .withItemIfPlanEntry(
                  ItemStatus.running(event.moduleName().value(), event.moduleName().value()))
              .withLogLine("[START] Module: " + event.moduleName().value());
      case MODULE_COMPLETED ->
          completeModule(state, event.moduleName().value())
              .withLogLine("[DONE]  Module: " + event.moduleName().value());
      case ITEM_STARTED ->
          startItem(state, event).withLogLine("[RUN]   " + event.item());
      case ITEM_COMPLETED -> applyItemCompleted(state, event);
      case ERROR ->
          state.withLogLine("[ERROR] " + event.item() + " in " + event.moduleName().value());
    };
  }

  private ExecutionScreenState applyItemCompleted(
      ExecutionScreenState state, ExecutionEvent event) {
    if (event.result().isEmpty()) {
      return state;
    }
    StepResult result = event.result().get();
    ItemResult itemResult = toItemResult(result);
    Duration elapsed = extractElapsed(result);
    if (state.hasPlanEntry(event.moduleName().value())) {
      return updatePlanEntry(state, event, itemResult, elapsed, result);
    }
    return state
        .withItem(
            ItemStatus.running(event.item(), event.moduleName().value())
                .withResult(itemResult, elapsed))
        .withLogLine(formatLogLine(event.item(), result));
  }

  private ExecutionScreenState startItem(ExecutionScreenState state, ExecutionEvent event) {
    if (state.hasPlanEntry(event.moduleName().value())) {
      if (hasHardTerminalResult(state, event.moduleName().value())) {
        return state;
      }
      return state.withItem(
          ItemStatus.running(event.moduleName().value(), event.moduleName().value()));
    }
    return state.withItem(ItemStatus.running(event.item(), event.moduleName().value()));
  }

  private ExecutionScreenState completeModule(ExecutionScreenState state, String moduleName) {
    if (!state.hasPlanEntry(moduleName)) {
      return state.withModuleCompleted();
    }
    if (hasTerminalResult(state, moduleName)) {
      return state.withModuleCompleted();
    }
    ItemStatus completed =
        ItemStatus.running(moduleName, moduleName).withResult(ItemResult.SUCCESS, Duration.ZERO);
    return state.withItem(completed).withModuleCompleted();
  }

  private boolean hasTerminalResult(ExecutionScreenState state, String moduleName) {
    return state.items().stream()
        .filter(item -> item.name().equals(moduleName) && item.module().equals(moduleName))
        .map(ItemStatus::result)
        .anyMatch(this::isTerminal);
  }

  private boolean hasHardTerminalResult(ExecutionScreenState state, String moduleName) {
    return state.items().stream()
        .filter(item -> item.name().equals(moduleName) && item.module().equals(moduleName))
        .map(ItemStatus::result)
        .anyMatch(this::isHardTerminal);
  }

  private boolean isTerminal(ItemResult result) {
    return result == ItemResult.SUCCESS
        || result == ItemResult.FAILED
        || result == ItemResult.INTERRUPTED
        || result == ItemResult.SKIPPED
        || result == ItemResult.DRY_RUN;
  }

  private boolean isHardTerminal(ItemResult result) {
    return result == ItemResult.FAILED || result == ItemResult.INTERRUPTED;
  }

  private ExecutionScreenState updatePlanEntry(
      ExecutionScreenState state,
      ExecutionEvent event,
      ItemResult itemResult,
      Duration elapsed,
      StepResult result) {
    ItemStatus status =
        ItemStatus.running(event.moduleName().value(), event.moduleName().value())
            .withResult(mergedResult(state, event.moduleName().value(), itemResult), elapsed)
            .withDetail(detail(result));
    return state.withItem(status).withLogLine(formatLogLine(event.item(), result));
  }

  private ItemResult mergedResult(
      ExecutionScreenState state, String moduleName, ItemResult incomingResult) {
    return state.items().stream()
        .filter(item -> item.name().equals(moduleName) && item.module().equals(moduleName))
        .findFirst()
        .map(item -> mergeResult(item.result(), incomingResult))
        .orElse(incomingResult);
  }

  private ItemResult mergeResult(ItemResult existing, ItemResult incoming) {
    if (existing == ItemResult.FAILED || incoming == ItemResult.FAILED) {
      return ItemResult.FAILED;
    }
    if (existing == ItemResult.INTERRUPTED || incoming == ItemResult.INTERRUPTED) {
      return ItemResult.INTERRUPTED;
    }
    if (existing == ItemResult.DRY_RUN || incoming == ItemResult.DRY_RUN) {
      return ItemResult.DRY_RUN;
    }
    if (existing == ItemResult.SKIPPED || incoming == ItemResult.SKIPPED) {
      return ItemResult.SKIPPED;
    }
    return incoming;
  }

  private Optional<String> detail(StepResult result) {
    return switch (result) {
      case StepResult.Failure f -> Optional.of(f.errorMessage());
      case StepResult.Skipped s -> Optional.of(s.reason());
      case StepResult.Paused p -> Optional.of(p.message());
      default -> Optional.empty();
    };
  }

  private ItemResult toItemResult(StepResult result) {
    return switch (result) {
      case StepResult.Success ignored -> ItemResult.SUCCESS;
      case StepResult.Failure ignored -> ItemResult.FAILED;
      case StepResult.Skipped ignored -> ItemResult.SKIPPED;
      case StepResult.DryRun ignored -> ItemResult.DRY_RUN;
      case StepResult.Paused ignored -> ItemResult.INTERRUPTED;
    };
  }

  private Duration extractElapsed(StepResult result) {
    return switch (result) {
      case StepResult.Success s -> s.elapsed();
      case StepResult.Failure f -> f.elapsed();
      case StepResult.Skipped ignored -> Duration.ZERO;
      case StepResult.DryRun ignored -> Duration.ZERO;
      case StepResult.Paused ignored -> Duration.ZERO;
    };
  }

  private String formatLogLine(String item, StepResult result) {
    return switch (result) {
      case StepResult.Success s ->
          String.format("[OK]    %s (%.1fs)", item, s.elapsed().toMillis() / 1000.0);
      case StepResult.Failure f ->
          String.format("[FAIL]  %s (exit %d): %s", item, f.exitCode(), f.errorMessage());
      case StepResult.Skipped s -> String.format("[SKIP]  %s: %s", item, s.reason());
      case StepResult.DryRun d ->
          String.format("[DRY]   %s: %s", item, String.join(" ", d.wouldExecute()));
      case StepResult.Paused p -> String.format("[PAUSE] %s: %s", item, p.message());
    };
  }
}
