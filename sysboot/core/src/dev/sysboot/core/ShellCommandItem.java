package dev.sysboot.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ShellCommandItem(
    String name,
    Optional<String> shellCommand,
    Optional<List<String>> argv,
    String shell,
    Optional<Path> workingDir,
    List<ShellEnvironmentVariable> environment,
    boolean sudo,
    List<Integer> allowedExitCodes,
    Optional<Path> creates,
    Optional<String> unless,
    Optional<String> confirm,
    Duration timeout) {

  public ShellCommandItem {
    Objects.requireNonNull(name);
    Objects.requireNonNull(shellCommand);
    Objects.requireNonNull(argv);
    Objects.requireNonNull(shell);
    Objects.requireNonNull(workingDir);
    Objects.requireNonNull(environment);
    Objects.requireNonNull(allowedExitCodes);
    Objects.requireNonNull(creates);
    Objects.requireNonNull(unless);
    Objects.requireNonNull(confirm);
    Objects.requireNonNull(timeout);
    environment = List.copyOf(environment);
    allowedExitCodes = allowedExitCodes.isEmpty() ? List.of(0) : List.copyOf(allowedExitCodes);
    validate(name, shellCommand, argv, shell, timeout);
  }

  public static ShellCommandItem shell(String command, String shell, Optional<Path> workingDir) {
    return shell(command, command, shell, workingDir);
  }

  public static ShellCommandItem shell(
      String name, String command, String shell, Optional<Path> workingDir) {
    return new ShellCommandItem(
        name,
        Optional.of(command),
        Optional.empty(),
        shell,
        workingDir,
        List.of(),
        false,
        List.of(0),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Duration.ofMinutes(30));
  }

  public List<String> commandPreview() {
    return command();
  }

  public List<String> command() {
    List<String> command =
        argv.map(List::copyOf).orElseGet(() -> List.of(shell, "-lc", shellCommand.orElseThrow()));
    return sudo ? prependSudo(command) : command;
  }

  public boolean allowsExitCode(int exitCode) {
    return allowedExitCodes.contains(exitCode);
  }

  private static List<String> prependSudo(List<String> command) {
    var result = new java.util.ArrayList<String>();
    result.add("sudo");
    result.addAll(command);
    return List.copyOf(result);
  }

  private static void validate(
      String name, Optional<String> shellCommand, Optional<List<String>> argv, String shell,
      Duration timeout) {
    if (name.isBlank()) {
      throw new IllegalArgumentException("command item name must not be blank");
    }
    if (shell.isBlank()) {
      throw new IllegalArgumentException("shell must not be blank");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
    boolean hasShellCommand = shellCommand.filter(value -> !value.isBlank()).isPresent();
    boolean hasArgv = argv.filter(values -> values.stream().allMatch(value -> !value.isBlank()))
        .filter(values -> !values.isEmpty()).isPresent();
    if (hasShellCommand == hasArgv) {
      throw new IllegalArgumentException("exactly one of shell command or argv is required");
    }
  }
}
