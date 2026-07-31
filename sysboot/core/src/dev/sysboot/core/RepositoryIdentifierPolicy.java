package dev.sysboot.core;

import java.util.Objects;
import java.util.regex.Pattern;

public final class RepositoryIdentifierPolicy {

  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private RepositoryIdentifierPolicy() {}

  public static String requireSafe(String value, String subject) {
    Objects.requireNonNull(value, subject + " must not be null");
    if (!SAFE_IDENTIFIER.matcher(value).matches()) {
      throw new IllegalArgumentException(
          subject
              + " must start with an alphanumeric character and contain only letters, digits,"
              + " '.', '_', or '-'");
    }
    return value;
  }
}
