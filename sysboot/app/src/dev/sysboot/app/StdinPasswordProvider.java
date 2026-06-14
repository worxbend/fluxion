package dev.sysboot.app;

import dev.sysboot.core.SudoPasswordProvider;
import java.io.Console;
import java.util.Optional;

public final class StdinPasswordProvider implements SudoPasswordProvider {

  private static final String NON_INTERACTIVE_MESSAGE =
      "Cannot prompt for sudo password in non-interactive mode. Re-run in a terminal.";

  @Override
  public Optional<char[]> requestPassword(String prompt) {
    Console console = System.console();
    if (console == null) {
      System.err.println("[fluxion] " + NON_INTERACTIVE_MESSAGE);
      return Optional.empty();
    }
    char[] password = console.readPassword("%s", prompt);
    return Optional.ofNullable(password);
  }
}
