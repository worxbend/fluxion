package dev.sysboot.cli.output;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.StepResult;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import picocli.CommandLine.Help.Ansi;

public final class StdoutExecutionEventListener implements ExecutionEventListener {

  private final Function<ExecutionEvent, Optional<String>> resumeCommandProvider;
  private final Supplier<Optional<Path>> statePathProvider;
  private final CommandTextRedactor redactor;
  private boolean streamOutput;
  private boolean itemLineOpen;
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
    switch (event.kind()) {
      case PHASE_STARTED ->
          System.out.println(
              Ansi.AUTO.string("@|bold,blue [PHASE ]|@ " + event.moduleName().value()));
      case PHASE_COMPLETED ->
          System.out.println(
              Ansi.AUTO.string("@|bold,blue [DONE  ]|@ phase " + event.moduleName().value()));
      case PHASE_FAILED ->
          System.out.println(
              Ansi.AUTO.string("@|bold,red [FAILED]|@ phase " + event.moduleName().value()));
      case PHASE_BLOCKED ->
          System.out.println(
              Ansi.AUTO.string(
                  "@|yellow [BLOCK ]|@ "
                      + event.moduleName().value()
                      + " waits for "
                      + redactor.redact(event.item())));
      case RESTART_REQUIRED -> printRestartRequired(event);
      case MODULE_STARTED ->
          System.out.println(
              Ansi.AUTO.string("@|bold,blue [MODULE]|@ " + event.moduleName().value()));
      case MODULE_COMPLETED ->
          System.out.println(
              Ansi.AUTO.string("@|bold,blue [DONE  ]|@ " + event.moduleName().value()));
      case ITEM_STARTED -> printItemStarted(event);
      case ITEM_OUTPUT -> printOutputLine(event);
      case ITEM_COMPLETED -> {
        reopenItemLineIfOutputInterrupted(event);
        event.result().ifPresent(result -> printResult(event, result));
        itemLineOpen = false;
      }
      case CANCELLED -> printCancelled(event);
      case ERROR ->
          System.out.println(
              Ansi.AUTO.string("@|bold,red [ERROR ]|@ " + redactor.redact(event.item())));
    }
  }

  private void printItemStarted(ExecutionEvent event) {
    System.out.print(
        Ansi.AUTO.string("  @|yellow  -->|@  " + redactor.redact(event.item()) + " ... "));
    itemLineOpen = true;
  }

  private void printOutputLine(ExecutionEvent event) {
    if (!streamOutput) {
      return;
    }
    event
        .outputLine()
        .filter(line -> !line.isBlank())
        .ifPresent(
            line -> {
              if (itemLineOpen) {
                System.out.println();
                itemLineOpen = false;
              }
              System.out.println("        " + redactor.redact(line));
            });
  }

  /**
   * ITEM_STARTED leaves its line open so the result can be appended to it. Streamed output breaks
   * that line, so the item is reprinted to keep the result readable.
   */
  private void reopenItemLineIfOutputInterrupted(ExecutionEvent event) {
    if (!itemLineOpen) {
      System.out.print(
          Ansi.AUTO.string("  @|yellow  -->|@  " + redactor.redact(event.item()) + " ... "));
    }
  }

  private void printCancelled(ExecutionEvent event) {
    closeOpenItemLine();
    System.out.println(
        Ansi.AUTO.string("@|bold,yellow [CANCEL]|@ stopped at your request; state was saved"));
    if (!event.item().isBlank()) {
      System.out.println("  Next plan entry: " + redactor.redact(event.item()));
    }
    resumeCommandProvider
        .apply(event)
        .ifPresent(command -> System.out.println("  Resume with: " + command));
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
    System.out.println(Ansi.AUTO.string("@|bold,yellow [RESTART]|@ " + event.moduleName().value()));
    for (String line : redactor.redact(event.item()).split("\n")) {
      System.out.println("  " + line);
    }
    resumeCommandProvider
        .apply(event)
        .ifPresent(command -> System.out.println("  Resume with: " + command));
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
        Ansi.AUTO.string(
            "@|green OK|@ ("
                + String.format("%.1fs", success.elapsed().toMillis() / 1000.0)
                + ")"));
  }

  private void printFailure(StepResult.Failure failure) {
    failed++;
    System.out.println(
        Ansi.AUTO.string(
            "@|red FAILED|@ (exit "
                + failure.exitCode()
                + "): "
                + redactor.redact(failure.errorMessage())));
  }

  private void printSkipped(StepResult.Skipped skip) {
    skipped++;
    System.out.println(Ansi.AUTO.string("@|yellow SKIPPED|@: " + redactor.redact(skip.reason())));
  }

  private void printDryRun(StepResult.DryRun dryRunResult) {
    dryRun++;
    var command = redactor.redactCommand(dryRunResult.wouldExecute());
    System.out.println(Ansi.AUTO.string("@|cyan DRY-RUN|@: " + String.join(" ", command)));
  }

  private void printPaused(ExecutionEvent event, StepResult.Paused paused) {
    this.paused++;
    System.out.println(
        Ansi.AUTO.string("@|bold,yellow PAUSED|@: " + redactor.redact(paused.message())));
    statePathProvider.get().ifPresent(path -> System.out.println("  State: " + path));
    paused.nextPlanEntry().ifPresent(next -> System.out.println("  Next plan entry: " + next));
    resumeCommandProvider
        .apply(event)
        .ifPresent(command -> System.out.println("  Resume with: " + command));
  }
}
