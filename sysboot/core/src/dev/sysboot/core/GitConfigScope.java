package dev.sysboot.core;

/** Which git configuration file an entry is written to. */
public enum GitConfigScope {
  GLOBAL("--global", false),
  SYSTEM("--system", true),
  LOCAL("--local", false);

  private final String flag;
  private final boolean privileged;

  GitConfigScope(String flag, boolean privileged) {
    this.flag = flag;
    this.privileged = privileged;
  }

  public String flag() {
    return flag;
  }

  /** System scope writes /etc/gitconfig and therefore needs sudo. */
  public boolean privileged() {
    return privileged;
  }
}
