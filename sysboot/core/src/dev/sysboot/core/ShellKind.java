package dev.sysboot.core;

public enum ShellKind {
  ZSH,
  BASH,
  SH;

  public String binaryName() {
    return name().toLowerCase();
  }
}
