package dev.sysboot.cli.error;

/**
 * User-facing command failure with a preclassified exit code.
 *
 * <p>Commands throw this exception for expected operational failures after they have enough context
 * to choose the correct process status. The global Picocli handler prints the message without a
 * stack trace.
 */
public final class CliFailureException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final ExitCode exitCode;

  /**
   * Creates a classified CLI failure.
   *
   * @param exitCode process status to return
   * @param message actionable error message for stderr
   */
  public CliFailureException(ExitCode exitCode, String message) {
    super(message);
    this.exitCode = exitCode;
  }

  /**
   * Creates a classified CLI failure with a cause preserved for debugging.
   *
   * @param exitCode process status to return
   * @param message actionable error message for stderr
   * @param cause underlying failure
   */
  public CliFailureException(ExitCode exitCode, String message, Throwable cause) {
    super(message, cause);
    this.exitCode = exitCode;
  }

  /**
   * Returns the status that should be used as the process exit code.
   *
   * @return classified exit code
   */
  public ExitCode exitCode() {
    return exitCode;
  }
}
