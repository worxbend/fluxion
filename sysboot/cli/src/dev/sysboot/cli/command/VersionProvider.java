package dev.sysboot.cli.command;

import dev.sysboot.core.FluxionVersion;
import picocli.CommandLine.IVersionProvider;

public final class VersionProvider implements IVersionProvider {

  public static final String VERSION = FluxionVersion.CURRENT;

  @Override
  public String[] getVersion() {
    return new String[] {"fluxion " + version()};
  }

  static String version() {
    return FluxionVersion.current();
  }
}
