package dev.sysboot.cli.error;

import dev.sysboot.config.ConfigLoadException;
import dev.sysboot.core.DisplayTextSanitizer;
import dev.sysboot.core.ExecutionPausedException;
import dev.sysboot.executor.BootstrapExecutionException;
import dev.sysboot.executor.ExecutionCancelledException;
import dev.sysboot.executor.PhasePlanningException;
import dev.sysboot.executor.ShellExecutionException;
import dev.sysboot.executor.StaleStateException;
import dev.sysboot.executor.StateReadException;
import dev.sysboot.executor.StateWriteException;
import dev.sysboot.executor.UnsupportedPackageManagerException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import picocli.CommandLine;
import picocli.CommandLine.IExecutionExceptionHandler;
import picocli.CommandLine.IParameterExceptionHandler;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

/**
 * Maps internal failures to concise CLI errors and deterministic process exit codes.
 *
 * <p>Normal user-facing failures must not print Java stack traces. Stack traces remain available to
 * tests and debuggers through preserved exception causes.
 */
public final class CliExceptionHandler
    implements IExecutionExceptionHandler, IParameterExceptionHandler {

  private final DisplayTextSanitizer sanitizer = new DisplayTextSanitizer();

  @Override
  public int handleExecutionException(
      Exception exception, CommandLine commandLine, ParseResult parseResult) {
    int exitCode = exitCodeFor(exception);
    commandLine.getErr().println("Error: " + messageFor(exception));
    return exitCode;
  }

  @Override
  public int handleParseException(ParameterException exception, String[] args) {
    CommandLine commandLine = exception.getCommandLine();
    commandLine.getErr().println("Error: " + messageFor(exception));
    commandLine.getErr().println();
    commandLine.usage(commandLine.getErr());
    return ExitCode.INVALID_INPUT.value();
  }

  private int exitCodeFor(Exception exception) {
    if (exception instanceof CliFailureException failure) {
      return failure.exitCode().value();
    }
    if (exception instanceof ExecutionPausedException paused) {
      return paused.exitCode();
    }
    if (exception instanceof ExecutionCancelledException) {
      return ExitCode.CANCELLED.value();
    }
    if (exception instanceof BootstrapExecutionException) {
      return ExitCode.EXTERNAL_DEPENDENCY_ERROR.value();
    }
    if (exception instanceof ConfigLoadException) {
      return ExitCode.CONFIGURATION_ERROR.value();
    }
    if (exception instanceof PhasePlanningException) {
      return ExitCode.CONFIGURATION_ERROR.value();
    }
    if (exception instanceof ParameterException
        || exception instanceof IllegalArgumentException
        || exception instanceof InvalidPathException
        || exception instanceof StaleStateException) {
      return ExitCode.INVALID_INPUT.value();
    }
    if (exception instanceof IOException
        || exception instanceof UncheckedIOException
        || exception instanceof StateReadException
        || exception instanceof StateWriteException) {
      return ExitCode.IO_ERROR.value();
    }
    if (exception instanceof ShellExecutionException
        || exception instanceof UnsupportedPackageManagerException) {
      return ExitCode.EXTERNAL_DEPENDENCY_ERROR.value();
    }
    return ExitCode.GENERAL_FAILURE.value();
  }

  private String messageFor(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank()
        ? sanitizer.sanitizeLine(exception.getClass().getSimpleName())
        : sanitizer.sanitizeLine(message);
  }
}
