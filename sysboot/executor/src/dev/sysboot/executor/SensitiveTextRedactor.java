package dev.sysboot.executor;

import dev.sysboot.core.ShellEnvironmentVariable;
import java.util.List;
import java.util.regex.Pattern;

final class SensitiveTextRedactor {

  private static final String MASK = "<redacted>";
  private static final Pattern URL_CREDENTIALS =
      Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://)[^\\s/@:]+(:[^\\s/@]*)?@");
  private static final Pattern TOKEN_ASSIGNMENT =
      Pattern.compile("(?i)(token|secret|password|passwd|credential)(=|:)[^\\s]+");
  private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]+");

  String redact(String text, List<ShellEnvironmentVariable> environment) {
    String redacted = text;
    for (ShellEnvironmentVariable variable : environment) {
      if (variable.sensitive() && !variable.value().isBlank()) {
        redacted = redacted.replace(variable.value(), MASK);
      }
    }
    redacted = URL_CREDENTIALS.matcher(redacted).replaceAll("$1" + MASK + "@");
    redacted = TOKEN_ASSIGNMENT.matcher(redacted).replaceAll("$1$2" + MASK);
    return BEARER.matcher(redacted).replaceAll("Bearer " + MASK);
  }

  List<String> redactCommand(List<String> command, List<ShellEnvironmentVariable> environment) {
    return command.stream().map(value -> redact(value, environment)).toList();
  }
}
