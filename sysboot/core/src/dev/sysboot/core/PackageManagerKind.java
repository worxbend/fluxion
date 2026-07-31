package dev.sysboot.core;

public enum PackageManagerKind {
  DNF,
  PACMAN,
  PARU,
  YAY,
  APT,
  FLATPAK,
  ZYPPER,
  CARGO;

  public boolean supportsSystemUpdate() {
    return switch (this) {
      case DNF, PACMAN, PARU, YAY, APT, ZYPPER -> true;
      case CARGO, FLATPAK -> false;
    };
  }
}
