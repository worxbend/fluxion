package dev.sysboot.core;

/** Whether a unit belongs to the system manager or the calling user's manager. */
public enum SystemdScope {
  SYSTEM(true),
  USER(false);

  private final String flag;
  private final boolean privileged;

  SystemdScope(boolean privileged) {
    this.privileged = privileged;
    this.flag = privileged ? "--system" : "--user";
  }

  public String flag() {
    return flag;
  }

  /** Only the system manager needs sudo; --user acts on the caller's own manager. */
  public boolean privileged() {
    return privileged;
  }
}
