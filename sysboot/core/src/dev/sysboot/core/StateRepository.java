package dev.sysboot.core;

import java.util.Optional;

public interface StateRepository {
  Optional<BootstrapState> load(String profileName);

  void save(BootstrapState state);

  BootstrapState recordSuccess(String profileName, StateEntry entry);

  void reset(String profileName);

  Optional<BootstrapState> forgetItem(String profileName, String itemKey);

  Optional<BootstrapState> forgetPhase(String profileName, String phaseName);
}
