package dev.sysboot.tui;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.ExecutionPausedException;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
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
    if (config == null) {
      out.print(DashboardScreen.render(new AppState.Dashboard(profilePaths, 0), detectOs()));
      return;
    }
    BootstrapConfig selected =
        selectionPrompt
            .select(config)
            .orElseThrow(() -> new IOException("TUI selection cancelled"));
    var screen =
        ExecutionScreenState.initial(selected.profileName().value(), selected.modules().size());
    stateRef.set(new AppState.Executing(screen, selected));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread runner = runOrchestrator(selected, dryRun, failure);
    var updated = renderUntilComplete(selected, screen, runner);
    throwIfFailed(failure);
    stateRef.set(new AppState.Completed(updated));
    renderFinal(updated);
  }

  private Thread runOrchestrator(
      BootstrapConfig config, boolean dryRun, AtomicReference<Throwable> failure) {
    return Thread.ofVirtual()
        .name("fluxion-tui-orchestrator")
        .start(
            () -> {
              try {
                if (dryRun) {
                  orchestrator.dryRun(config, eventListener);
                } else {
                  orchestrator.execute(config, eventListener);
                }
              } catch (ExecutionPausedException ignored) {
                // The pause event has already been emitted; render it as a controlled stop.
              } catch (RuntimeException e) {
                failure.set(e);
              }
            });
  }

  private ExecutionScreenState renderUntilComplete(
      BootstrapConfig config, ExecutionScreenState screen, Thread runner) throws IOException {
    ExecutionScreenState current = screen;
    while (runner.isAlive()) {
      current = eventListener.drainInto(current);
      stateRef.set(new AppState.Executing(current, config));
      renderExecution(current);
      sleepUntilNextFrame();
    }
    join(runner);
    return eventListener.drainInto(current);
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
