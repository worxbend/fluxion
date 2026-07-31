package dev.sysboot.executor;

import dev.sysboot.core.DisplayTextSanitizer;
import dev.sysboot.core.ShellEnvironmentVariable;
import java.util.List;

final class SensitiveTextRedactor {

  private static final String MASK = "<redacted>";
  static final int MIN_EXACT_SECRET_LENGTH = 4;
  private static final int MAX_EXACT_SECRET_CARRY = 4096;
  private final DisplayTextSanitizer sanitizer = new DisplayTextSanitizer();

  String redact(String text, List<ShellEnvironmentVariable> environment) {
    String normalized = sanitizer.normalizeLine(text);
    return sanitizer.sanitizeLine(replaceSensitiveValues(normalized, environment));
  }

  List<String> redactCommand(List<String> command, List<ShellEnvironmentVariable> environment) {
    List<String> replaced =
        command.stream()
            .map(sanitizer::normalizeLine)
            .map(value -> replaceSensitiveValues(value, environment))
            .toList();
    return sanitizer.sanitizeCommand(replaced);
  }

  StreamingLineRedactor streaming(List<ShellEnvironmentVariable> environment) {
    return new StreamingLineRedactor(environment);
  }

  private String replaceSensitiveValues(String text, List<ShellEnvironmentVariable> environment) {
    String redacted = text;
    for (ShellEnvironmentVariable variable : environment) {
      String value = sanitizer.normalizeLine(variable.value());
      if (!variable.sensitive() || value.isBlank() || !redacted.contains(value)) {
        continue;
      }
      if (value.length() < MIN_EXACT_SECRET_LENGTH) {
        return MASK;
      }
      redacted = redacted.replace(value, MASK);
    }
    return redacted;
  }

  final class StreamingLineRedactor {

    private final List<ShellEnvironmentVariable> environment;
    private final List<String> sensitiveValues;
    private final DisplayTextSanitizer.StreamingLineSanitizer lines = sanitizer.streamingLines();
    private final boolean maskAll;
    private String pending = "";

    private StreamingLineRedactor(List<ShellEnvironmentVariable> environment) {
      this.environment = List.copyOf(environment);
      this.sensitiveValues =
          environment.stream()
              .filter(ShellEnvironmentVariable::sensitive)
              .map(ShellEnvironmentVariable::value)
              .map(sanitizer::normalizeLine)
              .filter(value -> !value.isBlank())
              .toList();
      this.maskAll =
          sensitiveValues.stream().anyMatch(value -> value.length() > MAX_EXACT_SECRET_CARRY);
    }

    synchronized String redact(String text) {
      if (maskAll) {
        return MASK;
      }
      String normalized = pending + sanitizer.normalizeLine(text);
      pending = "";
      String replaced = replaceSensitiveValues(normalized, environment);
      int carryStart = exactSecretPrefixStart(replaced);
      if (carryStart >= 0) {
        pending = replaced.substring(carryStart);
        replaced = replaced.substring(0, carryStart);
      }
      return lines.sanitizeLine(replaced);
    }

    synchronized String finish() {
      if (maskAll) {
        return "";
      }
      String replaced = replaceSensitiveValues(pending, environment);
      pending = "";
      return lines.sanitizeLine(replaced) + lines.finish();
    }

    private int exactSecretPrefixStart(String text) {
      int earliest = -1;
      for (String secret : sensitiveValues) {
        for (int length = Math.min(secret.length() - 1, text.length()); length > 0; length--) {
          if (text.regionMatches(text.length() - length, secret, 0, length)) {
            int candidate = text.length() - length;
            earliest = earliest < 0 ? candidate : Math.min(earliest, candidate);
            break;
          }
        }
      }
      return earliest;
    }
  }
}
