package dev.sysboot.core;

/** What to do when a git-repo destination already exists. */
public enum GitRepoUpdate {
  /** Leave it alone. The safe default: a clone is provisioning, not synchronisation. */
  NONE,
  /** Fast-forward with {@code git pull --ff-only}, failing rather than merging. */
  PULL,
  /** Discard local changes and match the remote. Destructive, so never the default. */
  RESET_HARD
}
