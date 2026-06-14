package dev.sysboot.tui;

/**
 * Renders the pre-execution probe phase in the TUI.
 *
 * <p>Shows a progress bar and the currently-probing item. Rendered as a simple text block since
 * TamboUI widgets are unavailable at SNAPSHOT.
 */
public final class ProbePhaseScreen {

  private static final int BAR_WIDTH = 40;

  private ProbePhaseScreen() {}

  public static String render(AppState.ProbePhase state) {
    int total = state.totalItems();
    int done = state.probedSoFar();
    String current = state.currentItem();

    int filled = total == 0 ? BAR_WIDTH : (int) ((double) done / total * BAR_WIDTH);
    String bar = "=".repeat(filled) + " ".repeat(BAR_WIDTH - filled);

    return """
    ┌─ Probe Phase ──────────────────────────────────┐
    │  [%s] %3d/%3d │
    │  Checking: %-36s │
    └────────────────────────────────────────────────┘
    """
        .formatted(bar, done, total, truncate(current, 36));
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max - 3) + "...";
  }
}
