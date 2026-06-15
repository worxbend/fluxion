package dev.sysboot.cli.option;

import java.nio.file.Path;
import picocli.CommandLine.Option;

/**
 * Options shared by fluxion subcommands.
 *
 * <p>Global options are kept in a Picocli mixin so command classes stay focused on their behavior
 * while preserving consistent option names and help text.
 */
public final class GlobalOptions {

  @Option(
      names = {"-c", "--config"},
      description = "Config file path [default: ~/.config/fluxion/default.yaml]",
      paramLabel = "FILE")
  private Path configFile;

  @Option(
      names = {"--no-tui"},
      description = "Disable TUI, use plain stdout")
  private boolean noTui;

  @Option(
      names = {"-v", "--verbose"},
      description = "Verbose logging")
  private boolean verbose;

  /**
   * Resolves the configured YAML file path.
   *
   * @return the explicit {@code --config} path or the default profile under the user's home
   */
  public Path resolvedConfigFile() {
    if (configFile != null) {
      return configFile;
    }
    return Path.of(System.getProperty("user.home"), ".config", "fluxion", "default.yaml");
  }

  public boolean hasConfigFile() {
    return configFile != null;
  }

  public boolean noTui() {
    return noTui;
  }

  public boolean useTui() {
    return !noTui && System.console() != null;
  }

  public boolean verbose() {
    return verbose;
  }
}
