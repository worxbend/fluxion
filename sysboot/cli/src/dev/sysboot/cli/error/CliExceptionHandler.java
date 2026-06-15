package dev.sysboot.cli.error;

import dev.sysboot.config.ConfigLoadException;
import dev.sysboot.executor.StateReadException;
import dev.sysboot.executor.ShellExecutionException;
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

  @Override
  public int handleExecutionException(
      Exception exception, CommandLine commandLine, ParseResult parseResult) {
    ExitCode exitCode = exitCodeFor(exception);
    commandLine.getErr().println("Error: " + messageFor(exception));
    return exitCode.value();
  }

  @Override
  public int handleParseException(ParameterException exception, String[] args) {
    CommandLine commandLine = exception.getCommandLine();
    commandLine.getErr().println("Error: " + exception.getMessage());
    commandLine.getErr().println();
    commandLine.usage(commandLine.getErr());
    return ExitCode.INVALID_INPUT.value();
  }

  private ExitCode exitCodeFor(Exception exception) {
    if (exception instanceof CliFailureException failure) {
      return failure.exitCode();
    }
    if (exception instanceof ConfigLoadException) {
      return ExitCode.CONFIGURATION_ERROR;
    }
    if (exception instanceof ParameterException
        || exception instanceof IllegalArgumentException
        || exception instanceof InvalidPathException) {
      return ExitCode.INVALID_INPUT;
    }
    if (exception instanceof IOException
        || exception instanceof UncheckedIOException
        || exception instanceof StateReadException) {
      return ExitCode.IO_ERROR;
    }
    if (exception instanceof ShellExecutionException
        || exception instanceof UnsupportedPackageManagerException) {
      return ExitCode.EXTERNAL_DEPENDENCY_ERROR;
    }
    return ExitCode.GENERAL_FAILURE;
  }

  private String messageFor(Exception exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank()
        ? exception.getClass().getSimpleName()
        : message.strip();
  }
}
