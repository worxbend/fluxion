package dev.sysboot.core;

@FunctionalInterface
public interface PrivilegeGate {

  void verify(BootstrapConfig config);
}
