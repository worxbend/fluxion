package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellKind;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
    String commandString =
        command.stream().map(this::quoteIfNeeded).collect(Collectors.joining(" "));
    List<String> wrapped = List.of(shell.binaryName(), "--login", "-i", "-c", commandString);
    return delegate.run(wrapped, env, timeout);
  }

  private String quoteIfNeeded(String arg) {
    if (!arg.contains(" ") && !arg.contains("'") && !arg.contains("\"")) {
      return arg;
    }
    return "'" + arg.replace("'", "'\\''") + "'";
  }
}
