package dev.sysboot.core;

import java.util.List;

public interface PackageManagerExecutor {
  boolean supports(PackageManagerKind kind);

  List<String> installCommand(PackageName packageName);

  StepResult install(PackageName packageName);
}
