package dev.sysboot.cli;

import picocli.CommandLine;

/**
 * Native-image-friendly entry point for the sysboot command-line application.
 *
 * <p>The entry point creates all Picocli metadata explicitly and uses custom exception handlers so
 * user-facing failures return deterministic exit codes without stack traces.
 */
public final class Main {

  private static final String SLF4J_PROVIDER_PROPERTY = "slf4j.provider";
  private static final String SLF4J_VERBOSITY_PROPERTY = "slf4j.internal.verbosity";
  private static final String LOGBACK_PROVIDER =
      "ch.qos.logback.classic.spi.LogbackServiceProvider";

  private Main() {}

  /**
   * Creates the configured Picocli command line.
   *
   * @return command line configured with sysboot commands and error mapping
   */
  public static CommandLine commandLine() {
    configureLoggingProvider();
    var handler = new CliExceptionHandler();
    return new CommandLine(new SysbootCommand())
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExecutionExceptionHandler(handler)
        .setParameterExceptionHandler(handler);
  }

  /**
   * Runs the CLI process.
   *
   * @param args command-line arguments supplied by the operating system
   */
  public static void main(String[] args) {
    int exitCode = commandLine().execute(args);
    System.exit(exitCode);
  }

  private static void configureLoggingProvider() {
    if (System.getProperty(SLF4J_VERBOSITY_PROPERTY) == null) {
      System.setProperty(SLF4J_VERBOSITY_PROPERTY, "ERROR");
    }
    if (System.getProperty(SLF4J_PROVIDER_PROPERTY) == null) {
      System.setProperty(SLF4J_PROVIDER_PROPERTY, LOGBACK_PROVIDER);
    }
  }
}
