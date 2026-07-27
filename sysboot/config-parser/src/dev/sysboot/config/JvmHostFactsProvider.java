package dev.sysboot.config;

import dev.sysboot.core.HostFacts;
import dev.sysboot.core.HostFactsProvider;
import java.util.Optional;

final class JvmHostFactsProvider implements HostFactsProvider {

  @Override
  public HostFacts facts() {
    return new HostFacts(
        System.getProperty("os.name", "unknown"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        System.getProperty("os.arch", "unknown"));
  }

  @Override
  public boolean commandExists(String command) {
    return false;
  }
}
