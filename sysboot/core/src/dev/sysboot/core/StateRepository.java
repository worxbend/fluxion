package dev.sysboot.core;

import java.util.Optional;
import java.util.function.Function;

public interface StateRepository {
  Optional<BootstrapState> load(String profileName);

  void save(BootstrapState state);

  default BootstrapState update(
      String profileName, Function<Optional<BootstrapState>, BootstrapState> transition) {
    BootstrapState updated = transition.apply(load(profileName));
    save(updated);
    return updated;
  }

  BootstrapState recordSuccess(String profileName, StateEntry entry);

  void reset(String profileName);

  Optional<BootstrapState> forgetItem(String profileName, String itemKey);

  Optional<BootstrapState> forgetPhase(String profileName, String phaseName);
}
