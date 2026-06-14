package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;

public final class UnsupportedPackageManagerException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final PackageManagerKind kind;

  public UnsupportedPackageManagerException(PackageManagerKind kind) {
    super("No executor registered for package manager: " + kind);
    this.kind = kind;
  }

  public PackageManagerKind kind() {
    return kind;
  }
}
