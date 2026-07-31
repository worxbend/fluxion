package dev.sysboot.tui;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.executor.ExecutionCancelledException;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class SysbootTuiApp {

  private static final Duration DEFAULT_RENDER_INTERVAL = Duration.ofMillis(100);

  private final BootstrapOrchestrator orchestrator;
  private final TuiExecutionEventListener eventListener;
  private final TuiSudoPasswordProvider sudoPasswordProvider;
  private final List<String> profilePaths;
  private final AtomicReference<AppState> stateRef;
  private final PrintStream out;
  private final Duration renderInterval;
  private final TuiSelectionPrompt selectionPrompt;

  public SysbootTuiApp(
      BootstrapOrchestrator orchestrator,
      TuiExecutionEventListener eventListener,
      TuiSudoPasswordProvider sudoPasswordProvider,
      List<String> profilePaths) {
    this(
        orchestrator,
        eventListener,
        sudoPasswordProvider,
        profilePaths,
        System.out,
        DEFAULT_RENDER_INTERVAL,
        new TuiSelectionPrompt());
  }

  SysbootTuiApp(
      BootstrapOrchestrator orchestrator,
      TuiExecutionEventListener eventListener,
      TuiSudoPasswordProvider sudoPasswordProvider,
      List<String> profilePaths,
      PrintStream out,
      Duration renderInterval,
      TuiSelectionPrompt selectionPrompt) {
    this.orchestrator = orchestrator;
    this.eventListener = eventListener;
    this.sudoPasswordProvider = sudoPasswordProvider;
    this.profilePaths = List.copyOf(profilePaths);
    this.stateRef = new AtomicReference<>(new AppState.Dashboard(profilePaths, 0));
    this.out = out;
    this.renderInterval = renderInterval;
    this.selectionPrompt = selectionPrompt;
  }

  public void run(BootstrapConfig config, boolean dryRun) throws IOException {
    run(config, config, dryRun, dev.sysboot.core.CancellationSignal.never());
  }

  /** Enables the live command-output log pane. Off by default; see TuiExecutionEventListener. */
  public void showCommandOutput(boolean enabled) {
    eventListener.showCommandOutput(enabled);
  }

  public void runPrivilegePreflight(Runnable preflight) {
    AppState previous = stateRef.get();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread runner =
        Thread.ofVirtual()
            .name("fluxion-tui-privilege-preflight")
            .start(
                () -> {
                  try {
                    preflight.run();
                  } catch (Throwable throwable) {
                    failure.set(throwable);
                  }
                });
    try {
      boolean promptRendered = false;
      while (runner.isAlive()) {
        Optional<String> prompt = sudoPasswordProvider.pendingPrompt();
        if (prompt.isPresent() && !promptRendered) {
          renderSudoPrompt(previous, prompt.orElseThrow());
          promptRendered = true;
        }
        sleepForPreflight();
      }
      joinPreflight(runner);
      rethrowPreflightFailure(failure.get());
    } finally {
      stateRef.set(previous);
    }
  }

  /**
   * Runs the TUI, honouring a cancellation signal.
   *
   * <p>Ctrl-C previously hard-killed the JVM in TUI mode, which is the default whenever a console
   * is attached — so the graceful-stop behaviour only applied to {@code --no-tui} runs, i.e. the
   * ones least likely to be interrupted by hand.
   */
  public void run(
      BootstrapConfig config, boolean dryRun, dev.sysboot.core.CancellationSignal cancellation)
      throws IOException {
    run(config, config, dryRun, cancellation);
  }

  public void run(
      BootstrapConfig manifestConfig,
      BootstrapConfig selectableConfig,
      boolean dryRun,
      dev.sysboot.core.CancellationSignal cancellation)
      throws IOException {
    if (selectableConfig == null) {
      out.print(DashboardScreen.render(new AppState.Dashboard(profilePaths, 0), detectOs()));
      return;
    }
    BootstrapConfig selected =
        selectionPrompt.select(selectableConfig).orElseThrow(ExecutionCancelledException::new);
    boolean effectiveDryRun = dryRun || selected.policy().dryRunDefault().orElse(false);
    var screen = ExecutionScreenState.initial(selected);
    stateRef.set(new AppState.Executing(screen, selected));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread runner =
        runOrchestrator(manifestConfig, selected, effectiveDryRun, failure, cancellation);
    var updated = renderUntilComplete(selected, screen, runner);
    throwIfFailed(failure);
    stateRef.set(new AppState.Completed(updated));
    renderFinal(updated);
  }

  private Thread runOrchestrator(
      BootstrapConfig manifestConfig,
      BootstrapConfig executionConfig,
      boolean dryRun,
      AtomicReference<Throwable> failure,
      dev.sysboot.core.CancellationSignal cancellation) {
    return Thread.ofVirtual()
        .name("fluxion-tui-orchestrator")
        .start(
            () -> {
              try {
                if (dryRun) {
                  orchestrator.dryRun(executionConfig, eventListener);
                } else {
                  orchestrator.execute(
                      manifestConfig, executionConfig.phases(), eventListener, cancellation);
                }
              } catch (RuntimeException | Error throwable) {
                failure.set(throwable);
              }
            });
  }

  private ExecutionScreenState renderUntilComplete(
      BootstrapConfig config, ExecutionScreenState screen, Thread runner) throws IOException {
    try {
      ExecutionScreenState current = screen;
      boolean sudoPromptRendered = false;
      while (runner.isAlive() || eventListener.hasPendingEvents()) {
        Optional<String> sudoPrompt = sudoPasswordProvider.pendingPrompt();
        if (sudoPrompt.isPresent()) {
          if (!sudoPromptRendered) {
            renderSudoPrompt(current, config, sudoPrompt.orElseThrow());
            sudoPromptRendered = true;
          }
          sleepUntilNextFrame();
          continue;
        }
        if (sudoPromptRendered) {
          stateRef.set(new AppState.Executing(current, config));
        }
        sudoPromptRendered = false;
        Optional<ExecutionScreenState> updated = eventListener.drainOneInto(current);
        if (updated.isPresent()) {
          current = updated.get();
          stateRef.set(new AppState.Executing(current, config));
          renderExecution(current);
          continue;
        }
        stateRef.set(new AppState.Executing(current, config));
        renderExecution(current);
        sleepUntilNextFrame();
      }
      join(runner);
      return current;
    } catch (IOException failure) {
      if (!Thread.currentThread().isInterrupted()) {
        throw failure;
      }
      interruptWorker(runner);
      throw new ExecutionCancelledException(failure);
    }
  }

  private void interruptWorker(Thread runner) {
    boolean restoreInterrupt = Thread.interrupted();
    runner.interrupt();
    try {
      runner.join(Duration.ofSeconds(5));
    } catch (InterruptedException e) {
      restoreInterrupt = true;
    } finally {
      if (restoreInterrupt) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void renderSudoPrompt(
      ExecutionScreenState screen, BootstrapConfig config, String prompt) {
    renderSudoPrompt(new AppState.Executing(screen, config), prompt);
  }

  private void renderSudoPrompt(AppState previous, String prompt) {
    var state = new AppState.SudoPrompt(previous, prompt);
    stateRef.set(state);
    out.print("\u001b[H\u001b[2J");
    out.print(SudoPromptScreen.render(state));
    out.flush();
  }

  AppState currentState() {
    return stateRef.get();
  }

  private void sleepForPreflight() {
    try {
      Thread.sleep(renderInterval);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("TUI privilege preflight interrupted", e);
    }
  }

  private void joinPreflight(Thread runner) {
    try {
      runner.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("TUI privilege preflight interrupted", e);
    }
  }

  private void rethrowPreflightFailure(Throwable failure) {
    if (failure instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    if (failure instanceof Error error) {
      throw error;
    }
    if (failure != null) {
      throw new IllegalStateException("TUI privilege preflight failed", failure);
    }
  }

  private void renderExecution(ExecutionScreenState screen) {
    out.print("\u001b[H\u001b[2J");
    out.print(ExecutionScreen.render(screen));
    out.flush();
  }

  private void renderFinal(ExecutionScreenState screen) {
    out.print("\u001b[H\u001b[2J");
    out.print(CompletedScreen.render(new AppState.Completed(screen)));
    out.flush();
  }

  private void sleepUntilNextFrame() throws IOException {
    try {
      Thread.sleep(renderInterval);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("TUI execution interrupted", e);
    }
  }

  private void join(Thread runner) throws IOException {
    try {
      runner.join();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("TUI execution interrupted", e);
    }
  }

  private void throwIfFailed(AtomicReference<Throwable> failure) throws IOException {
    Throwable cause = failure.get();
    if (cause instanceof RuntimeException runtimeException) {
      throw runtimeException;
    }
    if (cause instanceof Error error) {
      throw error;
    }
    if (cause instanceof IOException ioException) {
      throw ioException;
    }
    if (cause != null) {
      throw new IOException("TUI execution failed", cause);
    }
  }

  public boolean sudoPromptPending() {
    return sudoPasswordProvider.isWaitingForPassword();
  }

  private String detectOs() {
    try {
      return java.nio.file.Files.readAllLines(java.nio.file.Path.of("/etc/os-release")).stream()
          .filter(line -> line.startsWith("PRETTY_NAME="))
          .findFirst()
          .map(line -> line.substring("PRETTY_NAME=".length()).replace("\"", ""))
          .orElse("Unknown Linux");
    } catch (IOException e) {
      return "Unknown Linux";
    }
  }
}
