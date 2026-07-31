package dev.sysboot.executor;

import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.SourceSetup;
import java.util.List;

final class SourceSetupRunner {

  private final SourceSetupExecutor executor;
  private final ItemExecution itemExecution;

  SourceSetupRunner(SourceSetupExecutor executor, ItemExecution itemExecution) {
    this.executor = executor;
    this.itemExecution = itemExecution;
  }

  Result execute(
      List<SourceSetup> sourceSetups,
      boolean continueOnFailure,
      ExecutionEventListener listener,
      CancellationSignal cancellation) {
    boolean failed = false;
    for (SourceSetup setup : sourceSetups) {
      if (cancellation.isCancelled()) {
        return Result.CANCELLED;
      }
      if (execute(setup, listener, cancellation)) {
        failed = true;
        if (!continueOnFailure) {
          return Result.FAILED;
        }
      }
    }
    if (cancellation.isCancelled()) {
      return Result.CANCELLED;
    }
    return failed ? Result.FAILED : Result.COMPLETED;
  }

  void preview(List<SourceSetup> sourceSetups, ExecutionEventListener listener) {
    for (SourceSetup setup : sourceSetups) {
      preview(setup, listener);
    }
  }

  private boolean execute(
      SourceSetup setup, ExecutionEventListener listener, CancellationSignal cancellation) {
    ModuleItem item = executor.item(setup);
    listener.onEvent(ExecutionEvent.moduleStarted(setup.name()));
    try {
      return ExecutionCancellation.with(
          cancellation,
          () ->
              itemExecution.executeWithoutStreaming(item, () -> executor.execute(setup), listener));
    } finally {
      listener.onEvent(ExecutionEvent.moduleCompleted(setup.name()));
    }
  }

  private void preview(SourceSetup setup, ExecutionEventListener listener) {
    ModuleItem item = executor.item(setup);
    listener.onEvent(ExecutionEvent.moduleStarted(setup.name()));
    itemExecution.preview(item, executor.commandPreview(setup), listener);
    listener.onEvent(ExecutionEvent.moduleCompleted(setup.name()));
  }

  enum Result {
    COMPLETED,
    FAILED,
    CANCELLED
  }
}
