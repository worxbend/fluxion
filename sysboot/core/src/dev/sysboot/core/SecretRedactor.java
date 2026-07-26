package dev.sysboot.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Masks credentials in text that is about to be shown to a user or written to a log.
 *
 * <p>This lives in {@code core} because every renderer needs it. It previously existed only in the
 * CLI, so the same command output was redacted on the plain path and printed verbatim in the TUI —
 * a secret echoed by a command reached the screen in exactly one of the two modes.
 *
 * <p>Redaction is best-effort pattern matching, not a guarantee. It is a second line of defence
 * behind not putting secrets in profiles at all.
 */
public final class SecretRedactor {

  private static final String MASK = "<redacted>";

  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^\\s/@:]+(:[^\\s/@]*)?@");
  private static final Pattern TOKEN_ASSIGNMENT =
      Pattern.compile("(?i)(token|secret|password|passwd|credential)(=|:)[^\\s]+");
  private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]+");

  public String redact(String text) {
    String redacted = URL_CREDENTIALS.matcher(text).replaceAll("$1" + MASK + "@");
    redacted = TOKEN_ASSIGNMENT.matcher(redacted).replaceAll("$1$2" + MASK);
    return BEARER.matcher(redacted).replaceAll("Bearer " + MASK);
  }

  public List<String> redactCommand(List<String> command) {
    return command.stream().map(this::redact).toList();
  }
}
