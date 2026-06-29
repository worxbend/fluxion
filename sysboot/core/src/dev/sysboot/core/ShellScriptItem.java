package dev.sysboot.core;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ShellScriptItem(
    String name,
    Optional<ScriptPath> script,
    Optional<URI> url,
    List<String> args,
    Optional<Path> workingDir,
    List<ShellEnvironmentVariable> environment,
    boolean sudo,
    List<Integer> allowedExitCodes,
    Optional<Path> creates,
    Optional<String> unless,
    Optional<String> confirm,
    Duration timeout) {

  public ShellScriptItem {
    Objects.requireNonNull(name);
    Objects.requireNonNull(script);
    Objects.requireNonNull(url);
    Objects.requireNonNull(args);
    Objects.requireNonNull(workingDir);
    Objects.requireNonNull(environment);
    Objects.requireNonNull(allowedExitCodes);
    Objects.requireNonNull(creates);
    Objects.requireNonNull(unless);
    Objects.requireNonNull(confirm);
    Objects.requireNonNull(timeout);
    args = List.copyOf(args);
    environment = List.copyOf(environment);
    allowedExitCodes = allowedExitCodes.isEmpty() ? List.of(0) : List.copyOf(allowedExitCodes);
    validate(name, script, url, timeout);
  }

  public static ShellScriptItem local(
      ScriptPath script, List<String> args, Optional<Path> workingDir) {
    return new ShellScriptItem(
        script.toString(),
        Optional.of(script),
        Optional.empty(),
        args,
        workingDir,
        List.of(),
        false,
        List.of(0),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Duration.ofMinutes(30));
  }

  public String key() {
    return script.map(ScriptPath::toString).orElseGet(() -> url.orElseThrow().toString());
  }

  public boolean allowsExitCode(int exitCode) {
    return allowedExitCodes.contains(exitCode);
  }

  private static void validate(
      String name, Optional<ScriptPath> script, Optional<URI> url, Duration timeout) {
    if (name.isBlank()) {
      throw new IllegalArgumentException("script item name must not be blank");
    }
    if (script.isPresent() == url.isPresent()) {
      throw new IllegalArgumentException("exactly one of script or url is required");
    }
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }
}
