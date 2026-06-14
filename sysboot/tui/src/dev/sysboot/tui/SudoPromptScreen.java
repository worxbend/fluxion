package dev.sysboot.tui;

public final class SudoPromptScreen {

  private SudoPromptScreen() {}

  public static String render(AppState.SudoPrompt state) {
    return "Sudo password required: " + state.prompt();
  }
}
