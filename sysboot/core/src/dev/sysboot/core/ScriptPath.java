package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;

public record ScriptPath(Path value) {

  public ScriptPath {
    Objects.requireNonNull(value, "Script path must not be null");
  }

  public ScriptPath resolve(Path base) {
    if (value.isAbsolute()) {
      return this;
    }
    return new ScriptPath(base.resolve(value).normalize());
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
