package dev.sysboot.config;

import java.nio.file.Path;

public final class ConfigLoadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final transient Path configFile;

  public ConfigLoadException(Path configFile, String message) {
    super("Failed to load config from " + configFile + ": " + message);
    this.configFile = configFile;
  }

  public ConfigLoadException(Path configFile, String message, Throwable cause) {
    super("Failed to load config from " + configFile + ": " + message, cause);
    this.configFile = configFile;
  }

  public Path configFile() {
    return configFile;
  }
}
