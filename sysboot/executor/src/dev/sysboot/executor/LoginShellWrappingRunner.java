package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellKind;
import dev.sysboot.core.ShellRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Wraps every command inside an interactive login shell subprocess so that PATH changes from
 * ~/.zshrc, ~/.cargo/env, etc. are visible to the command.
 *
 * <p>Used for phases with RestartPolicy.RequiresNewShell.
 */
public final class LoginShellWrappingRunner implements ShellRunner {

  private final ShellRunner delegate;
  private final ShellKind shell;

  public LoginShellWrappingRunner(ShellRunner delegate, ShellKind shell) {
    this.delegate = delegate;
    this.shell = shell;
  }

  @Override
  public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
    return delegate.run(forwardOrWrap(command), env, timeout);
  }

  @Override
  public ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Duration timeout,
      Consumer<String> outputSink) {
    return delegate.run(forwardOrWrap(command), env, timeout, outputSink);
  }

  @Override
  public ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Optional<Path> workingDirectory,
      Duration timeout) {
    return delegate.run(forwardOrWrap(command), env, workingDirectory, timeout);
  }

  @Override
  public ProcessResult run(
      List<String> command,
      Map<String, String> env,
      Optional<Path> workingDirectory,
      Duration timeout,
      Consumer<String> outputSink) {
    return delegate.run(forwardOrWrap(command), env, workingDirectory, timeout, outputSink);
  }

  private List<String> forwardOrWrap(List<String> command) {
    if (SudoCommand.isInvocation(command)) {
      return command;
    }
    String commandString = command.stream().map(this::posixQuote).collect(Collectors.joining(" "));
    return loginCommand(commandString);
  }

  /** Pure command transformation used to keep dry-run output identical to live execution. */
  public List<String> wrapCommand(List<String> command) {
    return forwardOrWrap(List.copyOf(command));
  }

  private List<String> loginCommand(String commandString) {
    String loginOption = shell == ShellKind.SH ? "-l" : "--login";
    return List.of(shell.binaryName(), loginOption, "-i", "-c", commandString);
  }

  private String posixQuote(String arg) {
    return "'" + arg.replace("'", "'\\''") + "'";
  }
}
