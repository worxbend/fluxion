package dev.sysboot.config;

import dev.sysboot.core.ToolPackagesModule;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Leaf helpers shared by both config frontends.
 *
 * <p>Fluxion has two mappers — one for the {@code jobs}/{@code steps} schema, one for the {@code
 * WorkstationProfile} manifest — and each had grown its own copy of the same small conversions.
 * {@code requireField} was byte-identical; {@code toolPackage}/{@code planToolPackage} and {@code
 * enumValue}/{@code planEnum} were the same logic under different names; the two {@code expandHome}
 * bodies differed only in whether they used an {@code if} or a ternary.
 *
 * <p>That is the cheap half of PLAN-i.md §11.2. The expensive half — one intermediate
 * representation so per-kind mapping is written once — is untouched, and remains the real fix.
 */
final class MappingSupport {

  private MappingSupport() {}

  static <T> T requireField(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException("Required field '" + fieldName + "' is missing");
    }
    return value;
  }

  /** Expands a leading {@code ~}, which is the only shell expansion a path field promises. */
  static String expandHome(String rawPath) {
    if (rawPath.equals("~")) {
      return System.getProperty("user.home");
    }
    return rawPath.startsWith("~/")
        ? System.getProperty("user.home") + rawPath.substring(1)
        : rawPath;
  }

  /** Enum parse where the field name is not worth naming separately. */
  static <E extends Enum<E>> E enumValue(Class<E> type, String raw) {
    return enumValue(type, raw, type.getSimpleName());
  }

  /** Parses a YAML enum value, accepting the hyphenated spelling used throughout the schema. */
  static <E extends Enum<E>> E enumValue(Class<E> type, String raw, String fieldName) {
    String normalized =
        requireField(raw, fieldName).strip().toUpperCase(Locale.ROOT).replace('-', '_');
    try {
      return Enum.valueOf(type, normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported value for " + fieldName + ": " + raw, e);
    }
  }

  /** Splits a {@code name@version} entry, leaving a bare name unpinned. */
  static ToolPackagesModule.ToolPackage toolPackage(String raw) {
    int at = raw.lastIndexOf('@');
    return at <= 0
        ? new ToolPackagesModule.ToolPackage(raw)
        : new ToolPackagesModule.ToolPackage(
            raw.substring(0, at), Optional.of(raw.substring(at + 1)));
  }

  /** Accepts bare seconds, {@code 30s}, {@code 5m}, {@code 500ms}, or ISO-8601. */
  static Duration duration(String raw) {
    String value = raw.strip();
    if (value.matches("\\d+")) {
      return Duration.ofSeconds(Long.parseLong(value));
    }
    if (value.endsWith("ms")) {
      return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
    }
    if (value.endsWith("s")) {
      return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
    }
    if (value.endsWith("m")) {
      return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
    }
    return Duration.parse(raw);
  }
}
