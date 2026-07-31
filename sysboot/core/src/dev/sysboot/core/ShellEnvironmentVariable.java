package dev.sysboot.core;

import java.util.Objects;
import java.util.regex.Pattern;

public record ShellEnvironmentVariable(String name, String value, boolean sensitive) {

  private static final Pattern PORTABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

  public ShellEnvironmentVariable {
    Objects.requireNonNull(name);
    Objects.requireNonNull(value);
    if (name.indexOf('\0') >= 0 || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("environment variables must not contain NUL");
    }
    if (!PORTABLE_NAME.matcher(name).matches()) {
      throw new IllegalArgumentException("environment variable name must be portable");
    }
  }
}
