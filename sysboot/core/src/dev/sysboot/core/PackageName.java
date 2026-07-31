package dev.sysboot.core;

import java.util.Objects;
import java.util.regex.Pattern;

public record PackageName(String value) {

  private static final Pattern UNSAFE_CHARS = Pattern.compile("[\\s$;|&`><\"'\\\\]");
  private static final Pattern URL_SCHEME = Pattern.compile("(?i)^(?:file|https?|ftp|git|ssh):");
  private static final Pattern SAFE_IDENTIFIER_SYNTAX =
      Pattern.compile("[A-Za-z0-9@][A-Za-z0-9@._+:/=~-]*");
  private static final Pattern REPOSITORY_QUALIFIED =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]*/[@A-Za-z0-9][A-Za-z0-9._+:=~-]*");
  private static final Pattern LOCAL_ARTIFACT =
      Pattern.compile(
          "(?i).*(?:\\.deb|\\.rpm|\\.apk|\\.pkg\\.tar(?:\\.[a-z0-9]+)+|\\.whl|\\.tar(?:\\.[a-z0-9]+)?|\\.tgz|\\.zip)$");

  public PackageName {
    Objects.requireNonNull(value, "Package name must not be null");
    value = value.strip();
    if (value.isBlank()) {
      throw new IllegalArgumentException("Package name must not be blank");
    }
    if (value.startsWith("-")) {
      throw new IllegalArgumentException(
          "Package name must not be interpreted as an option: " + value);
    }
    if (UNSAFE_CHARS.matcher(value).find()) {
      throw new IllegalArgumentException("Package name contains unsafe shell characters: " + value);
    }
    if (isAlternateSource(value)) {
      throw new IllegalArgumentException(
          "Package name must be a registry identifier, not a local or URL artifact: " + value);
    }
    if (!SAFE_IDENTIFIER_SYNTAX.matcher(value).matches()) {
      throw new IllegalArgumentException("Package name contains unsafe manager syntax: " + value);
    }
  }

  private static boolean isAlternateSource(String value) {
    if (value.startsWith("/")
        || value.startsWith("./")
        || value.startsWith("../")
        || value.startsWith("~/")
        || value.contains("://")
        || URL_SCHEME.matcher(value).find()
        || LOCAL_ARTIFACT.matcher(value).matches()) {
      return true;
    }
    return value.contains("/") && !REPOSITORY_QUALIFIED.matcher(value).matches();
  }

  @Override
  public String toString() {
    return value;
  }
}
