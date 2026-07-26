package dev.sysboot.cli.output;

import dev.sysboot.core.SecretRedactor;
import java.util.List;

/**
 * CLI-facing view of {@link SecretRedactor}.
 *
 * <p>The patterns moved to {@code core} so the TUI renderer can apply the same masking; this type
 * remains so existing CLI call sites and their tests are unchanged.
 */
public final class CommandTextRedactor {

  private final SecretRedactor delegate = new SecretRedactor();

  public String redact(String text) {
    return delegate.redact(text);
  }

  public List<String> redactCommand(List<String> command) {
    return delegate.redactCommand(command);
  }
}
