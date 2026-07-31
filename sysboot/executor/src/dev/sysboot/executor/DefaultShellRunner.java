package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class DefaultShellRunner implements ShellRunner {

  private static final Logger log = LoggerFactory.getLogger(DefaultShellRunner.class);
  private final SensitiveTextRedactor redactor = new SensitiveTextRedactor();

  @Override
  public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
    return run(command, env, timeout, ExecutionOutput.sink());
  }

  @Override
  public ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Duration timeout,
      Consumer<String> outputSink) {
    log.debug("Executing: {}", maskSensitive(command));
    List<String> effect = SudoCommand.forEffect(command);
    ProcessExecution.Request request =
        ProcessExecution.Request.of(effect, env, timeout).withOutputSink(outputSink);
    return ProcessExecution.run(sharedSudoSession(effect, request));
  }

  @Override
  public ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Optional<Path> workingDirectory,
      Duration timeout) {
    return run(command, env, workingDirectory, timeout, ExecutionOutput.sink());
  }

  @Override
  public ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Optional<Path> workingDirectory,
      Duration timeout,
      Consumer<String> outputSink) {
    log.debug("Executing: {}", maskSensitive(command));
    List<String> effect = SudoCommand.forEffect(command);
    ProcessExecution.Request request =
        ProcessExecution.Request.of(effect, env, timeout)
            .withWorkingDirectory(workingDirectory)
            .withOutputSink(outputSink);
    return ProcessExecution.run(sharedSudoSession(effect, request));
  }

  private ProcessExecution.Request sharedSudoSession(
      List<String> command, ProcessExecution.Request request) {
    return SudoCommand.isInvocation(command) ? request.inSharedSession() : request;
  }

  List<String> maskSensitive(List<String> command) {
    return redactor.redactCommand(command, ExecutionOutput.sensitiveEnvironment()).stream()
        .map(this::truncate)
        .toList();
  }

  private String truncate(String argument) {
    return argument.length() > 60 ? argument.substring(0, 57) + "..." : argument;
  }
}
