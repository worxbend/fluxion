package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerKind;
import java.util.List;
import java.util.Objects;

public final class PackageManagerExecutorRegistry {

  private final List<PackageManagerExecutor> executors;

  public PackageManagerExecutorRegistry(List<PackageManagerExecutor> executors) {
    Objects.requireNonNull(executors, "Executors list must not be null");
    this.executors = List.copyOf(executors);
  }

  public PackageManagerExecutor forKind(PackageManagerKind kind) {
    return executors.stream()
        .filter(e -> e.supports(kind))
        .findFirst()
        .orElseThrow(() -> new UnsupportedPackageManagerException(kind));
  }
}
