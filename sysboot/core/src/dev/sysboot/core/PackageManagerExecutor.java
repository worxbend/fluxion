package dev.sysboot.core;

public interface PackageManagerExecutor {
  boolean supports(PackageManagerKind kind);

  StepResult install(PackageName packageName);
}
