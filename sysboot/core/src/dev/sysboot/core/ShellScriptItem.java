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
    Duration timeout,
    Optional<Sha256Digest> sha256) {

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
    Objects.requireNonNull(sha256);
    args = List.copyOf(args);
    environment = List.copyOf(environment);
    allowedExitCodes = allowedExitCodes.isEmpty() ? List.of(0) : List.copyOf(allowedExitCodes);
    validate(name, script, url, timeout, sha256);
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
        Duration.ofMinutes(30),
        Optional.empty());
  }

  public String key() {
    return script.map(ScriptPath::toString).orElseGet(() -> PublicUrl.from(url.orElseThrow()));
  }

  public boolean allowsExitCode(int exitCode) {
    return allowedExitCodes.contains(exitCode);
  }

  private static void validate(
      String name,
      Optional<ScriptPath> script,
      Optional<URI> url,
      Duration timeout,
      Optional<Sha256Digest> sha256) {
    if (name.isBlank()) {
      throw new IllegalArgumentException("script item name must not be blank");
    }
    if (script.isPresent() == url.isPresent()) {
      throw new IllegalArgumentException("exactly one of script or url is required");
    }
    if (url.isPresent() != sha256.isPresent()) {
      throw new IllegalArgumentException(
          "remote scripts require sha256; local scripts must omit it");
    }
    url.ifPresent(ShellScriptItem::validateRemoteUrl);
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  private static void validateRemoteUrl(URI url) {
    if (!"https".equalsIgnoreCase(url.getScheme())
        || url.getHost() == null
        || url.getUserInfo() != null) {
      throw new IllegalArgumentException("remote script URL must be HTTPS without user-info");
    }
  }
}
