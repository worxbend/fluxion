package dev.sysboot.core;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ShellScriptModule(
    ModuleName name,
    List<ShellScriptItem> items,
    Optional<Path> workingDir,
    boolean continueOnError,
    Optional<String> probeCommand)
    implements BootstrapModule {

  public ShellScriptModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(items);
    Objects.requireNonNull(workingDir);
    Objects.requireNonNull(probeCommand);
    items = List.copyOf(items);
    if (items.isEmpty()) {
      throw new IllegalArgumentException("script items must not be empty");
    }
    requireUniqueItemNames(items);
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

  public ShellScriptModule(
      ModuleName name,
      ScriptPath script,
      List<String> args,
      Optional<Path> workingDir,
      boolean continueOnError,
      Optional<String> probeCommand) {
    this(
        name,
        List.of(ShellScriptItem.local(script, args, workingDir)),
        workingDir,
        continueOnError,
        probeCommand);
  }

  public ScriptPath script() {
    return items.getFirst().script().orElseThrow();
  }

  public List<String> args() {
    return items.getFirst().args();
  }

  private static void requireUniqueItemNames(List<ShellScriptItem> items) {
    var names = new HashSet<String>();
    for (ShellScriptItem item : items) {
      if (!names.add(item.name())) {
        throw new IllegalArgumentException("script item names must be unique: " + item.name());
      }
    }
  }
}
