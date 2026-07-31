package dev.sysboot.tui;

import dev.sysboot.core.DisplayTextSanitizer;
import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public final class TuiExecutionEventListener implements ExecutionEventListener {

  private static final int MAX_DRAIN_PER_TICK = 50;
  static final int EVENT_QUEUE_CAPACITY = 512;
  static final int STRUCTURAL_EVENT_RESERVE = 64;

  private final BlockingQueue<ExecutionEvent> eventQueue =
      new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);
  private final DisplayTextSanitizer sanitizer = new DisplayTextSanitizer();
  private final Map<OutputKey, DisplayTextSanitizer.StreamingLineSanitizer> outputSanitizers =
      new ConcurrentHashMap<>();
  private volatile boolean showCommandOutput;

  /** Enables the live command-output log pane, off by default. */
  public TuiExecutionEventListener showCommandOutput(boolean enabled) {
    this.showCommandOutput = enabled;
    return this;
  }

  @Override
  public void onEvent(ExecutionEvent event) {
    if (event.kind() == EventKind.ITEM_OUTPUT) {
      String safeLine = event.outputLine().map(line -> displayOutput(event, line)).orElse("");
      enqueueOutput(event, safeLine);
      return;
    }
    if (event.kind() == EventKind.ITEM_STARTED) {
      outputSanitizers.put(outputKey(event), sanitizer.streamingLines());
    }
    if (event.kind() == EventKind.ITEM_COMPLETED) {
      flushOutput(event);
    }
    enqueueStructural(event);
    if (event.kind() == EventKind.ITEM_COMPLETED) {
      outputSanitizers.remove(outputKey(event));
    }
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

  public Optional<ExecutionScreenState> drainOneInto(ExecutionScreenState state) {
    ExecutionEvent event = eventQueue.poll();
    if (event == null) {
      return Optional.empty();
    }
    return Optional.of(applyEvent(state, event));
  }

  public boolean hasPendingEvents() {
    return !eventQueue.isEmpty();
  }

  int pendingEventCount() {
    return eventQueue.size();
  }

  private void flushOutput(ExecutionEvent event) {
    Optional.ofNullable(outputSanitizers.get(outputKey(event)))
        .map(DisplayTextSanitizer.StreamingLineSanitizer::finish)
        .ifPresent(line -> enqueueOutput(event, line));
  }

  private void enqueueOutput(ExecutionEvent event, String safeLine) {
    if (showCommandOutput
        && !safeLine.isBlank()
        && eventQueue.remainingCapacity() > STRUCTURAL_EVENT_RESERVE) {
      if (!eventQueue.offer(
          ExecutionEvent.itemOutput(event.moduleName(), event.item(), safeLine))) {
        return;
      }
    }
  }

  private void enqueueStructural(ExecutionEvent event) {
    boolean interrupted = Thread.interrupted();
    while (!eventQueue.offer(event)) {
      if (discardQueuedOutput()) {
        continue;
      }
      try {
        eventQueue.put(event);
      } catch (InterruptedException ignored) {
        interrupted = true;
        continue;
      }
      break;
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean discardQueuedOutput() {
    for (ExecutionEvent queued : eventQueue) {
      if (queued.kind() == EventKind.ITEM_OUTPUT && eventQueue.remove(queued)) {
        return true;
      }
    }
    return false;
  }

  private ExecutionScreenState applyEvent(ExecutionScreenState state, ExecutionEvent event) {
    return switch (event.kind()) {
      case PHASE_STARTED -> state.withLogLine("[PHASE] " + displayModule(event));
      case PHASE_COMPLETED -> state.withLogLine("[PHASE DONE] " + displayModule(event));
      case PHASE_FAILED -> state.withLogLine("[PHASE FAILED] " + displayModule(event));
      case PHASE_BLOCKED -> state.withLogLine("[PHASE BLOCKED] " + displayModule(event));
      case RESTART_REQUIRED -> state.withLogLine("[RESTART REQUIRED] " + display(event.item()));
      case MODULE_STARTED ->
          state
              .withModule(displayModule(event))
              .withItemIfPlanEntry(
                  ItemStatus.running(event.moduleName().value(), event.moduleName().value()))
              .withLogLine("[START] Module: " + displayModule(event));
      case MODULE_COMPLETED ->
          completeModule(state, event.moduleName().value())
              .withLogLine("[DONE]  Module: " + displayModule(event));
      case ITEM_STARTED -> startItem(state, event).withLogLine("[RUN]   " + display(event.item()));
      case ITEM_OUTPUT -> applyItemOutput(state, event);
      case ITEM_COMPLETED -> applyItemCompleted(state, event);
      case CANCELLED -> state.withLogLine(cancellationMessage(event));
      case ERROR ->
          state.withLogLine("[ERROR] " + display(event.item()) + " in " + displayModule(event));
    };
  }

  /**
   * Streamed command output is off by default.
   *
   * <p>The TUI repaints the whole screen per drained event, so a chatty command turned the log pane
   * into a flicker storm and starved the rest of the render loop. Output is also redacted here: the
   * plain CLI path masked these same lines, so without this a secret echoed by a command reached
   * the screen in TUI mode only.
   */
  private ExecutionScreenState applyItemOutput(ExecutionScreenState state, ExecutionEvent event) {
    if (!showCommandOutput) {
      return state;
    }
    return event
        .outputLine()
        .filter(line -> !line.isBlank())
        .map(this::display)
        .map(state::withLogLine)
        .orElse(state);
  }

  private String cancellationMessage(ExecutionEvent event) {
    return event.item().isBlank()
        ? "[CANCELLED] stopped before " + displayModule(event)
        : "[CANCELLED] stopped before " + display(event.item());
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
            ItemStatus.running(display(event.item()), displayModule(event))
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
    return state.withItem(ItemStatus.running(display(event.item()), displayModule(event)));
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
      case StepResult.Failure f -> Optional.of(display(f.errorMessage()));
      case StepResult.Skipped s -> Optional.of(display(s.reason()));
      case StepResult.Paused p -> Optional.of(display(p.message()));
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
    String safeItem = display(item);
    return switch (result) {
      case StepResult.Success s ->
          String.format("[OK]    %s (%.1fs)", safeItem, s.elapsed().toMillis() / 1000.0);
      case StepResult.Failure f ->
          String.format(
              "[FAIL]  %s (exit %d): %s", safeItem, f.exitCode(), display(f.errorMessage()));
      case StepResult.Skipped s -> String.format("[SKIP]  %s: %s", safeItem, display(s.reason()));
      case StepResult.DryRun d ->
          String.format(
              "[DRY]   %s: %s",
              safeItem, String.join(" ", sanitizer.sanitizeCommand(d.wouldExecute())));
      case StepResult.Paused p -> String.format("[PAUSE] %s: %s", safeItem, display(p.message()));
    };
  }

  private String displayModule(ExecutionEvent event) {
    return display(event.moduleName().value());
  }

  private String display(String text) {
    return sanitizer.sanitizeLine(text);
  }

  private String displayOutput(ExecutionEvent event, String line) {
    return outputSanitizers
        .computeIfAbsent(outputKey(event), ignored -> sanitizer.streamingLines())
        .sanitizeLine(line);
  }

  private OutputKey outputKey(ExecutionEvent event) {
    return new OutputKey(event.moduleName().value(), event.item());
  }

  private record OutputKey(String module, String item) {}
}
