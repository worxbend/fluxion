package dev.sysboot.tui;

import dev.sysboot.core.DisplayTextSanitizer;

public final class DashboardScreen {

  private DashboardScreen() {}

  public static String render(AppState.Dashboard state, String detectedOs) {
    var sanitizer = new DisplayTextSanitizer();
    var profiles =
        state.availableProfiles().isEmpty()
            ? "  (no profiles found)"
            : String.join(
                System.lineSeparator(),
                state.availableProfiles().stream().map(sanitizer::sanitizeLine).toList());
    return """
    sysboot
    Detected OS: %s

    Profiles:
    %s
    """
        .formatted(sanitizer.sanitizeLine(detectedOs), profiles);
  }
}
