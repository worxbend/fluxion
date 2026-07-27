package dev.sysboot.core;

import java.util.List;

public interface PackageManagerExecutor {
  boolean supports(PackageManagerKind kind);

  default List<String> actionCommand(PackageManagerAction action) {
    throw new UnsupportedOperationException("Package manager action is not supported");
  }

  List<String> installCommand(PackageName packageName);

  default StepResult runAction(PackageManagerAction action) {
    throw new UnsupportedOperationException("Package manager action is not supported");
  }

  StepResult install(PackageName packageName);
}
