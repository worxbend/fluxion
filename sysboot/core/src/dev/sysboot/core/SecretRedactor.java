package dev.sysboot.core;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
  private static final String SENSITIVE_NAME =
      "(?:api[._-]?key|access[._-]?key|private[._-]?key|key[._-]?passphrase|passphrase"
          + "|authorization|token|secret|password|passwd|credentials?)";

  private static final Pattern URL_CREDENTIALS =
      Pattern.compile(
          "(?<![a-zA-Z0-9+.-])([a-zA-Z](?>[a-zA-Z0-9+.-]*)://)[^\\s/@:]+(:[^\\s/@]*)?@");
  private static final String QUOTED_OR_TOKEN = "(?:\"[^\"]*(?:\"|$)|'[^']*(?:'|$)|[^\\s,;\\]}]+)";
  private static final Pattern TOKEN_ASSIGNMENT =
      Pattern.compile(
          "(?i)(?<![a-z0-9])([\"']?"
              + SENSITIVE_NAME
              + "[\"']?)(\\s*[=:]\\s*)(?![\"']?(?:basic|bearer)\\b)"
              + QUOTED_OR_TOKEN);
  private static final Pattern SENSITIVE_ARGUMENT =
      Pattern.compile(
          "(?i)(?<![a-z0-9])(-{1,2}" + SENSITIVE_NAME + ")((?:\\s+|=))" + QUOTED_OR_TOKEN);
  private static final Pattern AUTHORIZATION_BASIC =
      Pattern.compile(
          "(?i)(?<![a-z0-9])((?:[\"']?authorization[\"']?)\\s*[=:]\\s*[\"']?basic\\s+)"
              + "[a-z0-9+/]+={0,2}(?=[\\s\"'}\\],;]|$)");
  private static final Pattern AUTHORIZATION_BEARER =
      Pattern.compile(
          "(?i)(?<![a-z0-9])((?:[\"']?authorization[\"']?)\\s*[=:]\\s*[\"']?bearer\\s+)"
              + "[a-z0-9._~+/=-]+(?=[\\s\"'}\\],;]|$)");
  private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[a-z0-9._~+/=-]+");
  private static final Pattern BASIC =
      Pattern.compile("(?i)(\\bbasic\\s+)[a-z0-9+/]{8,}={0,2}(?=[\\s\"'}\\],;]|$)");
  private static final Pattern CURL_USER =
      Pattern.compile("(?i)(?<!\\S)(--user(?:=|\\s+)|-u(?:\\s+|(?=[^\\s]*:)))" + QUOTED_OR_TOKEN);
  private static final Pattern PRIVATE_KEY =
      Pattern.compile(
          "-----BEGIN ([A-Z0-9 ]*PRIVATE KEY)-----.*?(?:-----END \\1-----|\\z)",
          Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
  private static final Pattern SENSITIVE_ENVIRONMENT_NAME =
      Pattern.compile("(^|[^a-z0-9])" + SENSITIVE_NAME + "($|[^a-z0-9])");

  public String redact(String text) {
    Objects.requireNonNull(text);
    String redacted = URL_CREDENTIALS.matcher(text).replaceAll("$1" + MASK + "@");
    redacted = AUTHORIZATION_BASIC.matcher(redacted).replaceAll("$1" + MASK);
    redacted = AUTHORIZATION_BEARER.matcher(redacted).replaceAll("$1" + MASK);
    redacted = BASIC.matcher(redacted).replaceAll("$1" + MASK);
    redacted = CURL_USER.matcher(redacted).replaceAll("$1" + MASK);
    redacted = TOKEN_ASSIGNMENT.matcher(redacted).replaceAll("$1$2" + MASK);
    redacted = SENSITIVE_ARGUMENT.matcher(redacted).replaceAll("$1$2" + MASK);
    redacted = BEARER.matcher(redacted).replaceAll("Bearer " + MASK);
    return PRIVATE_KEY.matcher(redacted).replaceAll(MASK);
  }

  public List<String> redactCommand(List<String> command) {
    Objects.requireNonNull(command);
    var redacted = new java.util.ArrayList<String>(command.size());
    boolean curl = isCurlCommand(command);
    boolean maskNext = false;
    for (String argument : command) {
      if (maskNext) {
        redacted.add(MASK);
        maskNext = false;
      } else {
        redacted.add(curl && isAttachedCurlUser(argument) ? "-u" + MASK : redact(argument));
        maskNext = isSensitiveOption(argument);
      }
    }
    return List.copyOf(redacted);
  }

  public static boolean isSensitiveName(String name) {
    Objects.requireNonNull(name);
    String canonical = name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    return SENSITIVE_ENVIRONMENT_NAME.matcher(canonical).find();
  }

  private boolean isSensitiveOption(String argument) {
    if (argument.startsWith("-u")) {
      return argument.equals("-u");
    }
    String name = argument.replaceFirst("^-+", "");
    return argument.equalsIgnoreCase("--user") || (!name.contains("=") && isSensitiveName(name));
  }

  private boolean isCurlCommand(List<String> command) {
    if (command.isEmpty()) {
      return false;
    }
    String executable = command.getFirst().replace('\\', '/');
    return executable.substring(executable.lastIndexOf('/') + 1).equalsIgnoreCase("curl");
  }

  private boolean isAttachedCurlUser(String argument) {
    return argument.startsWith("-u") && !argument.startsWith("--") && argument.length() > 2;
  }
}
