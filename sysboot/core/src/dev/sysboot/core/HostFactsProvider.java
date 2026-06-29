package dev.sysboot.core;

public interface HostFactsProvider {

  HostFacts facts();

  boolean commandExists(String command);
}
