package dev.sysboot.core;

import java.util.regex.Pattern;

/** Validation for immutable semantic-version release tags used in download URLs. */
public final class ReleaseTagPolicy {

  private static final Pattern EXACT =
      Pattern.compile("v\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?");

  private ReleaseTagPolicy() {}

  public static void requireExact(String field, String value) {
    if (value == null || !EXACT.matcher(value).matches()) {
      throw new IllegalArgumentException(field + " must pin an exact release such as v1.2.3");
    }
  }

  public static boolean isExact(String value) {
    return value != null && EXACT.matcher(value).matches();
  }
}
