package dev.sysboot.core;

import java.util.Locale;

public enum ShellKind {
  ZSH,
  BASH,
  SH;

  public String binaryName() {
    return name().toLowerCase(Locale.ROOT);
  }
}
