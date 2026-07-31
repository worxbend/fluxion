package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ShellRunner;
import java.util.Optional;

final class ModuleDispatcher {

  private final ModuleExecutorRegistry moduleExecutors;
  private final PhaseExecutors.Registry phaseExecutors;
  private final DirectModuleExecutor directExecutor;
  private final ItemExecution itemExecution;
  private final SkipEvaluator skipEvaluator;
  private final RunStateRecorder stateRecorder;

  ModuleDispatcher(
      ModuleExecutorRegistry moduleExecutors,
      PhaseExecutors.Registry phaseExecutors,
      DirectModuleExecutor directExecutor,
      ItemExecution itemExecution,
      SkipEvaluator skipEvaluator,
      RunStateRecorder stateRecorder) {
    this.moduleExecutors = moduleExecutors;
    this.phaseExecutors = phaseExecutors;
    this.directExecutor = directExecutor;
    this.itemExecution = itemExecution;
    this.skipEvaluator = skipEvaluator;
    this.stateRecorder = stateRecorder;
  }

  boolean execute(
      BootstrapModule module,
      ExecutionEventListener listener,
      ShellRunner shellRunner,
      CancellationSignal cancellation) {
    Optional<ModuleExecutor> moduleExecutor = moduleExecutors.find(module);
    if (moduleExecutor.isPresent()) {
      return executeRegistered(
          moduleExecutor.orElseThrow(), module, listener, shellRunner, cancellation);
    }
    PhaseExecutors executors = phaseExecutors.forRunner(shellRunner);
    Optional<StepBinding> binding = StepBinding.find(module);
    if (binding.isPresent()) {
      return executeBinding(binding.orElseThrow(), module, executors, listener);
    }
    return directExecutor.execute(module, listener, executors, shellRunner);
  }

  private boolean executeRegistered(
      ModuleExecutor executor,
      BootstrapModule module,
      ExecutionEventListener listener,
      ShellRunner shellRunner,
      CancellationSignal cancellation) {
    return executor.execute(
        module,
        listener,
        new ModuleExecutionContext(
            skipEvaluator, stateRecorder::recordSuccess, Optional.of(shellRunner), cancellation));
  }

  private boolean executeBinding(
      StepBinding binding,
      BootstrapModule module,
      PhaseExecutors executors,
      ExecutionEventListener listener) {
    return itemExecution.execute(
        binding.item(module), () -> binding.execute(module, executors), listener);
  }
}
