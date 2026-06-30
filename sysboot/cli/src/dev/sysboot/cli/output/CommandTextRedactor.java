package dev.sysboot.cli.output;

import java.util.List;
import java.util.regex.Pattern;

public final class CommandTextRedactor {

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
