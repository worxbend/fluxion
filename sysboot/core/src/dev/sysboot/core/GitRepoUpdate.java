package dev.sysboot.core;

/** Legacy config values retained while immutable Git repository provisioning replaces updates. */
public enum GitRepoUpdate {
  /** Verify the configured origin and exact commit without changing an existing destination. */
  NONE,
  /** Rejected by {@link GitRepoModule.GitRepo}; mutable pulls are not trusted provisioning. */
  PULL,
  /** Rejected by {@link GitRepoModule.GitRepo}; existing destinations are never reset. */
  RESET_HARD
}
