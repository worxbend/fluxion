package dev.sysboot.core;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Prepares untrusted process and event text for terminal display. */
public final class DisplayTextSanitizer {

  private static final Pattern OSC =
      Pattern.compile("\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)");
  private static final Pattern CSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
  private static final Pattern ESCAPE = Pattern.compile("\u001B(?:[@-_]|.)");
  private static final Pattern PRIVATE_KEY_BEGIN =
      Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
  private static final Pattern PRIVATE_KEY_END =
      Pattern.compile("-----END [A-Z0-9 ]*PRIVATE KEY-----", Pattern.CASE_INSENSITIVE);
  private static final String PRIVATE_KEY_PREFIX = "-----BEGIN ";
  private static final String PRIVATE_KEY_SUFFIX = "PRIVATE KEY";
  private static final String PEM_DASHES = "-----";
  private static final int MAX_PEM_MARKER_CARRY = 128;
  private static final String MASK = "<redacted>";

  private final SecretRedactor redactor = new SecretRedactor();

  public String sanitize(String text) {
    Objects.requireNonNull(text);
    return redactor.redact(stripTerminalControls(text, true));
  }

  public String sanitizeLine(String text) {
    Objects.requireNonNull(text);
    return redactor.redact(normalizeLine(text));
  }

  public List<String> sanitizeCommand(List<String> command) {
    Objects.requireNonNull(command);
    List<String> stripped = command.stream().map(this::normalizeLine).toList();
    return redactor.redactCommand(stripped);
  }

  /** Removes terminal controls without applying semantic secret matching. */
  public String normalizeLine(String text) {
    Objects.requireNonNull(text);
    return stripTerminalControls(text, false);
  }

  /** Creates state scoped to one output stream so multiline private keys stay masked. */
  public StreamingLineSanitizer streamingLines() {
    return new StreamingLineSanitizer();
  }

  private String stripTerminalControls(String text, boolean preserveNewlines) {
    String stripped = OSC.matcher(text).replaceAll("");
    stripped = CSI.matcher(stripped).replaceAll("");
    stripped = ESCAPE.matcher(stripped).replaceAll("");
    var safe = new StringBuilder(stripped.length());
    stripped.codePoints().forEach(value -> appendSafe(safe, value, preserveNewlines));
    return safe.toString();
  }

  private void appendSafe(StringBuilder safe, int value, boolean preserveNewlines) {
    if (value == '\n' && preserveNewlines) {
      safe.append('\n');
    } else if (value == '\u2028' || value == '\u2029') {
      safe.append(' ');
    } else if (Character.getType(value) == Character.FORMAT) {
      // Directional controls and other invisible format characters can spoof terminal output.
    } else if (!Character.isISOControl(value)) {
      safe.appendCodePoint(value);
    } else if (value == '\b') {
      removePrevious(safe);
    } else {
      safe.append(' ');
    }
  }

  private void removePrevious(StringBuilder safe) {
    if (!safe.isEmpty()) {
      int start = safe.offsetByCodePoints(safe.length(), -1);
      safe.delete(start, safe.length());
    }
  }

  public final class StreamingLineSanitizer {

    private boolean privateKey;
    private String pending = "";

    public synchronized String sanitizeLine(String text) {
      String normalized = pending + normalizeLine(text);
      pending = "";
      if (privateKey) {
        privateKey = !PRIVATE_KEY_END.matcher(normalized).find();
        return MASK;
      }
      var begin = PRIVATE_KEY_BEGIN.matcher(normalized);
      if (begin.find()) {
        privateKey = !PRIVATE_KEY_END.matcher(normalized.substring(begin.start())).find();
        String prefix = redactor.redact(normalized.substring(0, begin.start()));
        return prefix + MASK;
      }
      int carryStart = possibleMarkerStart(normalized);
      if (carryStart >= 0) {
        pending = normalized.substring(carryStart);
        if (pending.length() >= MAX_PEM_MARKER_CARRY) {
          privateKey = true;
          pending = "";
          return redactor.redact(normalized.substring(0, carryStart)) + MASK;
        }
        normalized = normalized.substring(0, carryStart);
      }
      return redactor.redact(normalized);
    }

    public synchronized String finish() {
      String trailing = pending;
      pending = "";
      return privateKey ? "" : redactor.redact(trailing);
    }

    private int possibleMarkerStart(String text) {
      int explicitPrefix = text.toUpperCase(java.util.Locale.ROOT).lastIndexOf(PRIVATE_KEY_PREFIX);
      if (explicitPrefix >= 0 && isPossibleMarker(text.substring(explicitPrefix))) {
        return explicitPrefix;
      }
      int lowerBound = Math.max(0, text.length() - MAX_PEM_MARKER_CARRY);
      for (int index = lowerBound; index < text.length(); index++) {
        if (isPossibleMarker(text.substring(index))) {
          return index;
        }
      }
      return -1;
    }

    private boolean isPossibleMarker(String candidate) {
      String upper = candidate.toUpperCase(java.util.Locale.ROOT);
      if (PRIVATE_KEY_PREFIX.startsWith(upper)) {
        return true;
      }
      if (!upper.startsWith(PRIVATE_KEY_PREFIX)) {
        return false;
      }
      String labelAndDashes = upper.substring(PRIVATE_KEY_PREFIX.length());
      int firstDash = labelAndDashes.indexOf('-');
      if (firstDash < 0) {
        return labelAndDashes.codePoints().allMatch(this::isPemLabelCharacter);
      }
      String label = labelAndDashes.substring(0, firstDash);
      String dashes = labelAndDashes.substring(firstDash);
      return label.endsWith(PRIVATE_KEY_SUFFIX)
          && label.codePoints().allMatch(this::isPemLabelCharacter)
          && PEM_DASHES.startsWith(dashes);
    }

    private boolean isPemLabelCharacter(int value) {
      return value == ' ' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9';
    }
  }
}
