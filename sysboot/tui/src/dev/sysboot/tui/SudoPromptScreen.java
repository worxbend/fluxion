package dev.sysboot.tui;

import dev.sysboot.core.DisplayTextSanitizer;

public final class SudoPromptScreen {

  private SudoPromptScreen() {}

  public static String render(AppState.SudoPrompt state) {
    return "Sudo password required: " + new DisplayTextSanitizer().sanitizeLine(state.prompt());
  }
}
