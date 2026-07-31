package dev.sysboot.cli.output;

import dev.sysboot.core.DisplayTextSanitizer;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.StepResult;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import picocli.CommandLine.Help.Ansi;

public final class StdoutExecutionEventListener implements ExecutionEventListener {

  private final Function<ExecutionEvent, Optional<String>> resumeCommandProvider;
  private final Supplier<Optional<Path>> statePathProvider;
  private final CommandTextRedactor redactor;
  private final Object eventLock = new Object();
  private final Map<OutputKey, DisplayTextSanitizer.StreamingLineSanitizer> outputSanitizers =
      new HashMap<>();
  private volatile boolean streamOutput;
  private volatile boolean itemLineOpen;
  private int succeeded;
  private int failed;
  private int skipped;
  private int dryRun;
  private int paused;

  public StdoutExecutionEventListener() {
    this(event -> Optional.empty(), Optional::empty);
  }

  public StdoutExecutionEventListener(
      Function<ExecutionEvent, Optional<String>> resumeCommandProvider) {
    this(resumeCommandProvider, Optional::empty);
  }

  public StdoutExecutionEventListener(
      Function<ExecutionEvent, Optional<String>> resumeCommandProvider,
      Supplier<Optional<Path>> statePathProvider) {
    this.resumeCommandProvider = resumeCommandProvider;
    this.statePathProvider = statePathProvider;
    this.redactor = new CommandTextRedactor();
  }

  /**
   * Prints each line of live command output as it arrives.
   *
   * <p>Off by default: the default output is one line per item, and interleaving a package
   * manager's own progress would bury it. {@code --verbose} turns it on.
   */
  public StdoutExecutionEventListener streamingOutput(boolean enabled) {
    this.streamOutput = enabled;
    return this;
  }

  @Override
  public void onEvent(ExecutionEvent event) {
    synchronized (eventLock) {
      onEventLocked(event);
    }
  }

  private void onEventLocked(ExecutionEvent event) {
    switch (event.kind()) {
      case PHASE_STARTED ->
          System.out.println(
              styled("@|bold,blue [PHASE ]|@ ") + display(event.moduleName().value()));
      case PHASE_COMPLETED ->
          System.out.println(
              styled("@|bold,blue [DONE  ]|@ phase ") + display(event.moduleName().value()));
      case PHASE_FAILED ->
          System.out.println(
              styled("@|bold,red [FAILED]|@ phase ") + display(event.moduleName().value()));
      case PHASE_BLOCKED ->
          System.out.println(
              styled("@|yellow [BLOCK ]|@ ")
                  + display(event.moduleName().value())
                  + " waits for "
                  + display(event.item()));
      case RESTART_REQUIRED -> printRestartRequired(event);
      case MODULE_STARTED ->
          System.out.println(
              styled("@|bold,blue [MODULE]|@ ") + display(event.moduleName().value()));
      case MODULE_COMPLETED ->
          System.out.println(
              styled("@|bold,blue [DONE  ]|@ ") + display(event.moduleName().value()));
      case ITEM_STARTED -> {
        outputSanitizers.put(outputKey(event), new DisplayTextSanitizer().streamingLines());
        printItemStarted(event);
      }
      case ITEM_OUTPUT -> printOutputLine(event);
      case ITEM_COMPLETED -> {
        flushOutput(event);
        reopenItemLineIfOutputInterrupted(event);
        event.result().ifPresent(result -> printResult(event, result));
        outputSanitizers.remove(outputKey(event));
        itemLineOpen = false;
      }
      case CANCELLED -> printCancelled(event);
      case ERROR -> System.out.println(styled("@|bold,red [ERROR ]|@ ") + display(event.item()));
    }
  }

  private void printItemStarted(ExecutionEvent event) {
    System.out.print(styled("  @|yellow  -->|@  ") + display(event.item()) + " ... ");
    itemLineOpen = true;
  }

  private void printOutputLine(ExecutionEvent event) {
    event.outputLine().map(line -> displayOutput(event, line)).ifPresent(this::printSafeOutput);
  }

  private void flushOutput(ExecutionEvent event) {
    Optional.ofNullable(outputSanitizers.get(outputKey(event)))
        .map(DisplayTextSanitizer.StreamingLineSanitizer::finish)
        .ifPresent(this::printSafeOutput);
  }

  private void printSafeOutput(String line) {
    if (!streamOutput || line.isBlank()) {
      return;
    }
    if (itemLineOpen) {
      System.out.println();
      itemLineOpen = false;
    }
    System.out.println("        " + line);
  }

  /**
   * ITEM_STARTED leaves its line open so the result can be appended to it. Streamed output breaks
   * that line, so the item is reprinted to keep the result readable.
   */
  private void reopenItemLineIfOutputInterrupted(ExecutionEvent event) {
    if (!itemLineOpen) {
      System.out.print(styled("  @|yellow  -->|@  ") + display(event.item()) + " ... ");
    }
  }

  private void printCancelled(ExecutionEvent event) {
    closeOpenItemLine();
    System.out.println(styled("@|bold,yellow [CANCEL]|@ stopped at your request; state was saved"));
    if (!event.item().isBlank()) {
      System.out.println("  Next plan entry: " + display(event.item()));
    }
    resumeCommandProvider
        .apply(event)
        .ifPresent(command -> System.out.println("  Resume with: " + display(command)));
  }

  private void closeOpenItemLine() {
    if (itemLineOpen) {
      System.out.println();
      itemLineOpen = false;
    }
  }

  public void printSummary() {
    System.out.printf(
        "Final counts: ok=%d failed=%d skipped=%d dry_run=%d paused=%d%n",
        succeeded, failed, skipped, dryRun, paused);
  }

  private void printRestartRequired(ExecutionEvent event) {
    System.out.println(styled("@|bold,yellow [RESTART]|@ ") + display(event.moduleName().value()));
    for (String line : redactor.redactMultiline(event.item()).split("\n")) {
      System.out.println("  " + line);
    }
    resumeCommandProvider
        .apply(event)
        .ifPresent(command -> System.out.println("  Resume with: " + display(command)));
  }

  private void printResult(ExecutionEvent event, StepResult result) {
    switch (result) {
      case StepResult.Success s -> printSuccess(s);
      case StepResult.Failure f -> printFailure(f);
      case StepResult.Skipped s -> printSkipped(s);
      case StepResult.DryRun d -> printDryRun(d);
      case StepResult.Paused p -> printPaused(event, p);
    }
  }

  private void printSuccess(StepResult.Success success) {
    succeeded++;
    System.out.println(
        styled("@|green OK|@ (")
            + String.format("%.1fs", success.elapsed().toMillis() / 1000.0)
            + ")");
  }

  private void printFailure(StepResult.Failure failure) {
    failed++;
    System.out.println(
        styled("@|red FAILED|@ (exit ")
            + failure.exitCode()
            + "): "
            + display(failure.errorMessage()));
  }

  private void printSkipped(StepResult.Skipped skip) {
    skipped++;
    System.out.println(styled("@|yellow SKIPPED|@: ") + display(skip.reason()));
  }

  private void printDryRun(StepResult.DryRun dryRunResult) {
    dryRun++;
    var command = redactor.redactCommand(dryRunResult.wouldExecute());
    System.out.println(styled("@|cyan DRY-RUN|@: ") + String.join(" ", command));
  }

  private void printPaused(ExecutionEvent event, StepResult.Paused paused) {
    this.paused++;
    System.out.println(styled("@|bold,yellow PAUSED|@: ") + display(paused.message()));
    statePathProvider
        .get()
        .ifPresent(path -> System.out.println("  State: " + display(path.toString())));
    paused
        .nextPlanEntry()
        .ifPresent(next -> System.out.println("  Next plan entry: " + display(next)));
    resumeCommandProvider
        .apply(event)
        .ifPresent(command -> System.out.println("  Resume with: " + display(command)));
  }

  private String styled(String markup) {
    return Ansi.AUTO.string(markup);
  }

  private String display(String text) {
    return redactor.redact(text);
  }

  private String displayOutput(ExecutionEvent event, String line) {
    return outputSanitizers
        .computeIfAbsent(outputKey(event), ignored -> new DisplayTextSanitizer().streamingLines())
        .sanitizeLine(line);
  }

  private OutputKey outputKey(ExecutionEvent event) {
    return new OutputKey(event.moduleName().value(), event.item());
  }

  private record OutputKey(String module, String item) {}
}
