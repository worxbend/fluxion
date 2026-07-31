package dev.sysboot.cli.output;

import dev.sysboot.core.DisplayTextSanitizer;
import java.util.List;

/**
 * CLI-facing view of {@link DisplayTextSanitizer}.
 *
 * <p>The patterns moved to {@code core} so the TUI renderer can apply the same masking; this type
 * remains so existing CLI call sites and their tests are unchanged.
 */
public final class CommandTextRedactor {

  private final DisplayTextSanitizer delegate = new DisplayTextSanitizer();

  public String redact(String text) {
    return delegate.sanitizeLine(text);
  }

  public String redactMultiline(String text) {
    return delegate.sanitize(text);
  }

  public List<String> redactCommand(List<String> command) {
    return delegate.sanitizeCommand(command);
  }
}
