package dev.sysboot.tui;

public final class DashboardScreen {

  private DashboardScreen() {}

  public static String render(AppState.Dashboard state, String detectedOs) {
    var profiles =
        state.availableProfiles().isEmpty()
            ? "  (no profiles found)"
            : String.join(System.lineSeparator(), state.availableProfiles());
    return """
    sysboot
    Detected OS: %s

    Profiles:
    %s
    """
        .formatted(detectedOs, profiles);
  }
}
