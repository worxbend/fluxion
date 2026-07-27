package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    return ProcessExecution.run(
        ProcessExecution.Request.of(command, env, timeout).withOutputSink(outputSink));
  }

  private List<String> maskSensitive(List<String> command) {
    return command.stream()
        .map(arg -> redactor.redact(arg, List.of()))
        .map(this::truncate)
        .toList();
  }

  private String truncate(String argument) {
    return argument.length() > 60 ? argument.substring(0, 57) + "..." : argument;
  }
}
