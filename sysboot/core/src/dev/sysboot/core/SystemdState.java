package dev.sysboot.core;

/** Desired runtime state of a unit. */
public enum SystemdState {
  STARTED,
  STOPPED,
  /** Leave the current runtime state alone and only manage enablement. */
  UNCHANGED
}
