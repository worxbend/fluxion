package dev.sysboot.executor;

import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.ShellRunner;
import java.util.Optional;

record ModuleExecutionContext(
    SkipEvaluator skipEvaluator,
    ItemSuccessRecorder successRecorder,
    Optional<ShellRunner> shellRunner,
    CancellationSignal cancellation) {

  ModuleExecutionContext(SkipEvaluator skipEvaluator, ItemSuccessRecorder successRecorder) {
    this(skipEvaluator, successRecorder, Optional.empty(), CancellationSignal.never());
  }
}
