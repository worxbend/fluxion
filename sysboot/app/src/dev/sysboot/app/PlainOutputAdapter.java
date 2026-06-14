package dev.sysboot.app;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SudoPasswordProvider;

public final class PlainOutputAdapter implements OutputAdapter {

  private final SudoPasswordProvider sudoPasswordProvider;
  private final ExecutionEventListener eventListener;

  public PlainOutputAdapter() {
    this.sudoPasswordProvider = new StdinPasswordProvider();
    this.eventListener = this::handleEvent;
  }

  @Override
  public ExecutionEventListener eventListener() {
    return eventListener;
  }

  @Override
  public SudoPasswordProvider sudoPasswordProvider() {
    return sudoPasswordProvider;
  }

  @Override
  public void start() {}

  @Override
  public void stop() {}

  private void handleEvent(ExecutionEvent event) {
    switch (event.kind()) {
      case PHASE_STARTED -> System.out.println("\n[fluxion] Phase: " + event.moduleName().value());
      case PHASE_COMPLETED ->
          System.out.println("[fluxion] Phase complete: " + event.moduleName().value());
      case PHASE_FAILED ->
          System.err.println("[fluxion] ⚠ Phase FAILED: " + event.moduleName().value());
      case PHASE_BLOCKED ->
          System.out.println(
              "[fluxion] ⦸ Blocked: "
                  + event.moduleName().value()
                  + " (depends on failed: "
                  + event.item()
                  + ")");
      case RESTART_REQUIRED -> {
        System.out.println("[fluxion]");
        System.out.println("[fluxion] ⚠ Restart required:");
        for (String line : event.item().split("\n")) {
          System.out.println("[fluxion]   " + line);
        }
      }
      case ITEM_COMPLETED ->
          event
              .result()
              .ifPresent(
                  r -> {
                    String label =
                        switch (r) {
                          case StepResult.Success s ->
                              "✓ "
                                  + s.item()
                                  + s.detectedVersion().map(v -> " (" + v + ")").orElse("");
                          case StepResult.Failure f ->
                              "✗ " + f.item() + " (exit " + f.exitCode() + ")";
                          case StepResult.Skipped sk -> "○ " + sk.item() + "  already installed";
                          case StepResult.DryRun dr -> "~ " + dr.item() + "  [dry-run]";
                        };
                    System.out.println("[fluxion]   " + label);
                  });
      case MODULE_STARTED -> System.out.println("[fluxion]");
      default -> {
        /* suppress noisy events */
      }
    }
  }
}
