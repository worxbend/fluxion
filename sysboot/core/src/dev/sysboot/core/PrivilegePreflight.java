package dev.sysboot.core;

@FunctionalInterface
public interface PrivilegePreflight {

  void verify();

  static PrivilegePreflight none() {
    return () -> {};
  }
}
