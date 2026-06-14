package dev.sysboot.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ShellScriptModule(
    ModuleName name,
    ScriptPath script,
    List<String> args,
    Optional<Path> workingDir,
    boolean continueOnError,
    Optional<String> probeCommand)
    implements BootstrapModule {

  public ShellScriptModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(script);
    Objects.requireNonNull(args);
    Objects.requireNonNull(workingDir);
    Objects.requireNonNull(probeCommand);
    args = List.copyOf(args);
  }

  public ShellScriptModule(
      ModuleName name,
      ScriptPath script,
      List<String> args,
      Optional<Path> workingDir,
      boolean continueOnError) {
    this(name, script, args, workingDir, continueOnError, Optional.empty());
  }

  public ShellScriptModule(
      ModuleName name, ScriptPath script, List<String> args, boolean continueOnError) {
    this(name, script, args, Optional.empty(), continueOnError, Optional.empty());
  }
}
