package dev.sysboot.cli;

import dev.sysboot.cli.command.SysbootCommand;
import dev.sysboot.cli.error.CliExceptionHandler;
import picocli.CommandLine;

/**
 * Native-image-friendly entry point for the Fluxion command-line application.
 *
 * <p>The entry point creates all Picocli metadata explicitly and uses custom exception handlers so
 * user-facing failures return deterministic exit codes without stack traces.
 */
public final class Main {

  private static final String SLF4J_PROVIDER_PROPERTY = "slf4j.provider";
  private static final String SLF4J_VERBOSITY_PROPERTY = "slf4j.internal.verbosity";
  private static final String LOGBACK_STATUS_LISTENER_PROPERTY = "logback.statusListenerClass";
  private static final String LOG_LEVEL_PROPERTY = "fluxion.log.level";
  private static final String LOGBACK_PROVIDER =
      "ch.qos.logback.classic.spi.LogbackServiceProvider";
  private static final String LOGBACK_ERROR_STATUS_LISTENER =
      "dev.sysboot.executor.ErrorOnlyLogbackStatusListener";

  private Main() {}

  /**
   * Creates the configured Picocli command line.
   *
   * @return command line configured with Fluxion commands and error mapping
   */
  public static CommandLine commandLine() {
    return commandLine(new String[0]);
  }

  /**
   * Creates the configured Picocli command line.
   *
   * @param args raw process arguments, inspected for logging flags before any logger is created
   * @return command line configured with Fluxion commands and error mapping
   */
  public static CommandLine commandLine(String[] args) {
    configureLoggingProvider();
    configureLogLevel(args);
    var handler = new CliExceptionHandler();
    CommandLine commandLine =
        new CommandLine(new SysbootCommand())
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setExecutionExceptionHandler(handler)
            .setParameterExceptionHandler(handler);
    enableStandardHelp(commandLine);
    return commandLine;
  }

  /**
   * Runs the CLI process.
   *
   * @param args command-line arguments supplied by the operating system
   */
  public static void main(String[] args) {
    int exitCode = commandLine(args).execute(args);
    System.exit(exitCode);
  }

  /**
   * Sets the log level before the first logger is created.
   *
   * <p>Logback reads its level when the configuration is first loaded, which happens on the first
   * {@code getLogger} call — earlier than Picocli finishes parsing. Scanning the raw arguments is
   * the only way to honour {@code --verbose} for logging as well as for output.
   */
  private static void configureLogLevel(String[] args) {
    if (System.getProperty(LOG_LEVEL_PROPERTY) != null) {
      return;
    }
    boolean verbose =
        java.util.Arrays.stream(args).anyMatch(arg -> "-v".equals(arg) || "--verbose".equals(arg));
    System.setProperty(LOG_LEVEL_PROPERTY, verbose ? "DEBUG" : "WARN");
  }

  private static void configureLoggingProvider() {
    if (System.getProperty(SLF4J_VERBOSITY_PROPERTY) == null) {
      System.setProperty(SLF4J_VERBOSITY_PROPERTY, "ERROR");
    }
    if (System.getProperty(SLF4J_PROVIDER_PROPERTY) == null) {
      System.setProperty(SLF4J_PROVIDER_PROPERTY, LOGBACK_PROVIDER);
    }
    if (System.getProperty(LOGBACK_STATUS_LISTENER_PROPERTY) == null) {
      System.setProperty(LOGBACK_STATUS_LISTENER_PROPERTY, LOGBACK_ERROR_STATUS_LISTENER);
    }
  }

  private static void enableStandardHelp(CommandLine commandLine) {
    commandLine.getCommandSpec().mixinStandardHelpOptions(true);
    commandLine.getSubcommands().values().forEach(Main::enableStandardHelp);
  }
}
