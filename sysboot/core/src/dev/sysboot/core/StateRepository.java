package dev.sysboot.core;

import java.util.Optional;

public interface StateRepository {
  Optional<BootstrapState> load(String profileName);

  void save(BootstrapState state);

  void recordSuccess(String profileName, StateEntry entry);
}
