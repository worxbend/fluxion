package dev.sysboot.core;

import java.util.Objects;

public sealed interface RestartPolicy
    permits RestartPolicy.None, RestartPolicy.PromptLogout, RestartPolicy.RequiresNewShell {

  record None() implements RestartPolicy {}

  record PromptLogout(String message) implements RestartPolicy {
    public PromptLogout {
      Objects.requireNonNull(message);
    }
  }

  record RequiresNewShell(ShellKind shell) implements RestartPolicy {
    public RequiresNewShell {
      Objects.requireNonNull(shell);
    }
  }
}
