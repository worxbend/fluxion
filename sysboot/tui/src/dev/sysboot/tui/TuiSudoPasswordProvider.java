package dev.sysboot.tui;

import dev.sysboot.core.SudoPasswordProvider;
import java.util.Arrays;
import java.util.Optional;

public final class TuiSudoPasswordProvider implements SudoPasswordProvider {

  private final PasswordReader passwordReader;
  private volatile String pendingPrompt;

  public TuiSudoPasswordProvider() {
    this(new ConsolePasswordReader());
  }

  TuiSudoPasswordProvider(PasswordReader passwordReader) {
    this.passwordReader = passwordReader;
  }

  @Override
  public Optional<char[]> requestPassword(String prompt) {
    if (!passwordReader.isAvailable()) {
      return Optional.empty();
    }
    this.pendingPrompt = prompt;
    char[] supplied = null;
    try {
      supplied = passwordReader.readPassword(prompt);
      if (supplied == null || supplied.length == 0) {
        return Optional.empty();
      }
      return Optional.of(Arrays.copyOf(supplied, supplied.length));
    } finally {
      if (supplied != null) {
        Arrays.fill(supplied, '\0');
      }
      this.pendingPrompt = null;
    }
  }

  public boolean isWaitingForPassword() {
    return pendingPrompt != null;
  }

  public Optional<String> pendingPrompt() {
    return Optional.ofNullable(pendingPrompt);
  }

  interface PasswordReader {

    boolean isAvailable();

    char[] readPassword(String prompt);
  }

  private static final class ConsolePasswordReader implements PasswordReader {

    @Override
    public boolean isAvailable() {
      return System.console() != null;
    }

    @Override
    public char[] readPassword(String prompt) {
      var console = System.console();
      return console == null ? null : console.readPassword("%s ", prompt);
    }
  }
}
