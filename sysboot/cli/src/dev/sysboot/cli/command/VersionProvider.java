package dev.sysboot.cli.command;

import picocli.CommandLine.IVersionProvider;

public final class VersionProvider implements IVersionProvider {

  public static final String VERSION = "1.0.0";

  @Override
  public String[] getVersion() {
    return new String[] {"fluxion " + version()};
  }

  static String version() {
    String override = propertyOverride();
    return override.isBlank() ? VERSION : override;
  }

  private static String propertyOverride() {
    return System.getProperty("fluxion.version", "").strip();
  }
}
