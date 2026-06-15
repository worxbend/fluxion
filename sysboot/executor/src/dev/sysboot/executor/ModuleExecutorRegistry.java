package dev.sysboot.executor;

import dev.sysboot.core.BootstrapModule;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class ModuleExecutorRegistry {

  private final List<ModuleExecutor> executors;

  ModuleExecutorRegistry(List<ModuleExecutor> executors) {
    this.executors = List.copyOf(Objects.requireNonNull(executors));
  }

  Optional<ModuleExecutor> find(BootstrapModule module) {
    Objects.requireNonNull(module);
    return executors.stream().filter(executor -> executor.supports(module)).findFirst();
  }
}
