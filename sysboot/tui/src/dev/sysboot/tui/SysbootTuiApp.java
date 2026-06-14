package dev.sysboot.tui;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class SysbootTuiApp {

  private final BootstrapOrchestrator orchestrator;
  private final TuiExecutionEventListener eventListener;
  private final TuiSudoPasswordProvider sudoPasswordProvider;
  private final List<String> profilePaths;
  private final AtomicReference<AppState> stateRef;

  public SysbootTuiApp(
      BootstrapOrchestrator orchestrator,
      TuiExecutionEventListener eventListener,
      TuiSudoPasswordProvider sudoPasswordProvider,
      List<String> profilePaths) {
    this.orchestrator = orchestrator;
    this.eventListener = eventListener;
    this.sudoPasswordProvider = sudoPasswordProvider;
    this.profilePaths = List.copyOf(profilePaths);
    this.stateRef = new AtomicReference<>(new AppState.Dashboard(profilePaths, 0));
  }

  public void run(BootstrapConfig config, boolean dryRun) throws IOException {
    if (config == null) {
      System.out.print(DashboardScreen.render(new AppState.Dashboard(profilePaths, 0), detectOs()));
      return;
    }
    var screen =
        ExecutionScreenState.initial(config.profileName().value(), config.modules().size());
    stateRef.set(new AppState.Executing(screen, config));
    if (dryRun) {
      orchestrator.dryRun(config, eventListener);
    } else {
      orchestrator.execute(config, eventListener);
    }
    var updated = eventListener.drainInto(screen);
    stateRef.set(new AppState.Completed(updated));
    System.out.print(CompletedScreen.render(new AppState.Completed(updated)));
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
