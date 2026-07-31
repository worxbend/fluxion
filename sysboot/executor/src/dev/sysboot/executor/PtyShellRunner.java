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

/**
 * Shell runner used by the interactive application path.
 *
 * <p>Sudo authentication is completed by {@link SudoPrivilegePreflight}. Effects always use
 * non-interactive sudo with closed stdin, so a cached password can never reach an effect process.
 */
public final class PtyShellRunner implements ShellRunner {

  private static final Logger log = LoggerFactory.getLogger(PtyShellRunner.class);
  private final SudoSession sudoSession;

  public PtyShellRunner(SudoSession sudoSession) {
    this.sudoSession = java.util.Objects.requireNonNull(sudoSession);
  }

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
    log.debug("Interactive executing: {}", firstArgument(command));
    ProcessExecution.Request request = request(command, env, timeout).withOutputSink(outputSink);
    return ProcessExecution.run(request);
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
    log.debug("Interactive executing: {}", firstArgument(command));
    ProcessExecution.Request request =
        request(command, env, timeout)
            .withWorkingDirectory(workingDirectory)
            .withOutputSink(outputSink);
    return ProcessExecution.run(request);
  }

  private String firstArgument(List<String> command) {
    return command.isEmpty() ? "<empty command>" : command.getFirst();
  }

  private ProcessExecution.Request request(
      List<String> command, Map<String, String> environment, Duration timeout) {
    if (SudoCommand.isInvocation(command)) {
      sudoSession.ensureAuthenticated("Re-authenticate sudo to continue this profile");
    }
    List<String> effect = SudoCommand.forEffect(command);
    ProcessExecution.Request request = ProcessExecution.Request.of(effect, environment, timeout);
    return SudoCommand.isInvocation(effect) ? request.inSharedSession() : request;
  }
}
