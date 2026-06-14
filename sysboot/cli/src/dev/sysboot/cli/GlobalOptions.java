package dev.sysboot.cli;

import java.nio.file.Path;
import picocli.CommandLine.Option;

public final class GlobalOptions {

  @Option(
      names = {"-c", "--config"},
      description = "Config file path [default: ~/.config/sysboot/default.yaml]",
      paramLabel = "FILE")
  public Path configFile;

  @Option(
      names = {"--no-tui"},
      description = "Disable TUI, use plain stdout")
  public boolean noTui;

  @Option(
      names = {"-v", "--verbose"},
      description = "Verbose logging")
  public boolean verbose;

  public Path resolvedConfigFile() {
    if (configFile != null) {
      return configFile;
    }
    return Path.of(System.getProperty("user.home"), ".config", "sysboot", "default.yaml");
  }
}
