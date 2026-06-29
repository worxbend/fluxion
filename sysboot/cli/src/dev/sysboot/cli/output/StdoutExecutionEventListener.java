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
                      + event.item()));
      case RESTART_REQUIRED -> printRestartRequired(event);
      case MODULE_STARTED ->
          System.out.println(
              Ansi.AUTO.string("@|bold,blue [MODULE]|@ " + event.moduleName().value()));
      case MODULE_COMPLETED ->
          System.out.println(
              Ansi.AUTO.string("@|bold,blue [DONE  ]|@ " + event.moduleName().value()));
      case ITEM_STARTED ->
          System.out.print(Ansi.AUTO.string("  @|yellow  -->|@  " + event.item() + " ... "));
      case ITEM_COMPLETED -> event.result().ifPresent(result -> printResult(event, result));
      case ERROR -> System.out.println(Ansi.AUTO.string("@|bold,red [ERROR ]|@ " + event.item()));
    }
  }

  private void printRestartRequired(ExecutionEvent event) {
    System.out.println(Ansi.AUTO.string("@|bold,yellow [RESTART]|@ " + event.moduleName().value()));
    for (String line : event.item().split("\n")) {
      System.out.println("  " + line);
    }
    resumeCommandProvider
        .apply(event)
        .ifPresent(command -> System.out.println("  Resume with: " + command));
  }

  private void printResult(ExecutionEvent event, StepResult result) {
    switch (result) {
      case StepResult.Success s ->
          System.out.println(
              Ansi.AUTO.string(
                  "@|green OK|@ ("
                      + String.format("%.1fs", s.elapsed().toMillis() / 1000.0)
                      + ")"));
      case StepResult.Failure f ->
          System.out.println(
              Ansi.AUTO.string("@|red FAILED|@ (exit " + f.exitCode() + "): " + f.errorMessage()));
      case StepResult.Skipped s ->
          System.out.println(Ansi.AUTO.string("@|yellow SKIPPED|@: " + s.reason()));
      case StepResult.DryRun d ->
          System.out.println(
              Ansi.AUTO.string("@|cyan DRY-RUN|@: " + String.join(" ", d.wouldExecute())));
      case StepResult.Paused p -> printPaused(event, p);
    }
  }

  private void printPaused(ExecutionEvent event, StepResult.Paused paused) {
    System.out.println(Ansi.AUTO.string("@|bold,yellow PAUSED|@: " + paused.message()));
    statePathProvider.get().ifPresent(path -> System.out.println("  State: " + path));
    paused.nextPlanEntry().ifPresent(next -> System.out.println("  Next plan entry: " + next));
    resumeCommandProvider
        .apply(event)
        .ifPresent(command -> System.out.println("  Resume with: " + command));
  }
}
