package dev.sysboot.core;

import java.util.Objects;
import java.util.regex.Pattern;

final class FlatpakApplicationPolicy {

  private static final Pattern APP_ID =
      Pattern.compile("[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z][A-Za-z0-9_-]*){2,}");

  private FlatpakApplicationPolicy() {}

  static String requireAppId(String value) {
    Objects.requireNonNull(value, "Flatpak app ID must not be null");
    if (!APP_ID.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "Flatpak app must be a registry app ID, not an option, path, URL, or alternate source: "
              + value);
    }
    return value;
  }
}
