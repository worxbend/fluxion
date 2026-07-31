package dev.sysboot.executor;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.StepResult;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class ItemExecution {

  private final SkipEvaluator skipEvaluator;
  private final RunStateRecorder stateRecorder;

  ItemExecution(SkipEvaluator skipEvaluator, RunStateRecorder stateRecorder) {
    this.skipEvaluator = skipEvaluator;
    this.stateRecorder = stateRecorder;
  }

  SkipEvaluator skipEvaluator() {
    return skipEvaluator;
  }

  boolean execute(
      ModuleName moduleName,
      String itemKey,
      ItemType itemType,
      Supplier<StepResult> action,
      ExecutionEventListener listener) {
    return execute(new ModuleItem(moduleName, itemKey, itemType), action, listener);
  }

  boolean execute(ModuleItem item, Supplier<StepResult> action, ExecutionEventListener listener) {
    return execute(item, action, listener, Streaming.ENABLED);
  }

  boolean executeWithoutStreaming(
      ModuleItem item, Supplier<StepResult> action, ExecutionEventListener listener) {
    return execute(item, action, listener, Streaming.DISABLED);
  }

  Consumer<String> outputSink(
      ModuleName moduleName, String itemKey, ExecutionEventListener listener) {
    return line -> listener.onEvent(ExecutionEvent.itemOutput(moduleName, itemKey, line));
  }

  void emitSkipped(
      ModuleName moduleName,
      String itemKey,
      SkipDecision.Skip skip,
      ExecutionEventListener listener) {
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            moduleName, itemKey, new StepResult.Skipped(itemKey, skip.reason().toString())));
  }

  void preview(ModuleItem item, java.util.List<String> command, ExecutionEventListener listener) {
    listener.onEvent(ExecutionEvent.itemStarted(item.moduleName(), item.key()));
    SkipDecision decision = skipEvaluator.evaluate(item);
    if (decision instanceof SkipDecision.Skip skip) {
      emitSkipped(item.moduleName(), item.key(), skip, listener);
      return;
    }
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            item.moduleName(), item.key(), new StepResult.DryRun(item.key(), command)));
  }

  private boolean execute(
      ModuleItem item,
      Supplier<StepResult> action,
      ExecutionEventListener listener,
      Streaming streaming) {
    listener.onEvent(ExecutionEvent.itemStarted(item.moduleName(), item.key()));
    SkipDecision decision = skipEvaluator.evaluate(item);
    if (decision instanceof SkipDecision.Skip skip) {
      emitSkipped(item.moduleName(), item.key(), skip, listener);
      return false;
    }
    StepResult result = run(item, action, listener, streaming);
    listener.onEvent(ExecutionEvent.itemCompleted(item.moduleName(), item.key(), result));
    stateRecorder.recordSuccess(item.moduleName(), item.key(), item.itemType(), result);
    return result instanceof StepResult.Failure;
  }

  private StepResult run(
      ModuleItem item,
      Supplier<StepResult> action,
      ExecutionEventListener listener,
      Streaming streaming) {
    if (streaming == Streaming.DISABLED) {
      return action.get();
    }
    return ExecutionOutput.withSink(outputSink(item.moduleName(), item.key(), listener), action);
  }

  private enum Streaming {
    ENABLED,
    DISABLED
  }
}
