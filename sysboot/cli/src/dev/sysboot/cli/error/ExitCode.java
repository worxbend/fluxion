package dev.sysboot.cli.error;

/**
 * Process exit codes returned by the Fluxion command-line interface.
 *
 * <p>The values are intentionally small and stable so shell scripts can branch on them without
 * parsing human-readable output.
 */
public enum ExitCode {
  /** Command completed successfully. */
  SUCCESS(0),

  /** An unexpected failure occurred. */
  GENERAL_FAILURE(1),

  /** Command-line arguments or user input were invalid. */
  INVALID_INPUT(2),

  /** The YAML configuration could not be loaded or validated. */
  CONFIGURATION_ERROR(3),

  /** A local file-system or stream operation failed. */
  IO_ERROR(4),

  /** An external command, package manager, or runtime dependency failed. */
  EXTERNAL_DEPENDENCY_ERROR(5),

  /** Execution paused at an explicit interrupt checkpoint. */
  PAUSED(75);

  private final int value;

  ExitCode(int value) {
    this.value = value;
  }

  /**
   * Returns the numeric process status used by POSIX shells.
   *
   * @return stable integer exit code
   */
  public int value() {
    return value;
  }
}
