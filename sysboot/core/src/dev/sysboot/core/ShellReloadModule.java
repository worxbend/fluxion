package dev.sysboot.core;

import java.util.Objects;

public record ShellReloadModule(ModuleName name, ShellKind shell, String description)
    implements BootstrapModule {

  public ShellReloadModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(shell);
    Objects.requireNonNull(description);
  }

  public ShellReloadModule(ModuleName name, ShellKind shell) {
    this(name, shell, "");
  }
}
