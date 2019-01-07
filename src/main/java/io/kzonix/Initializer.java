package io.kzonix;

public interface Initializer {
  void applyConfiguration();
  void initCache();
  void initDaemon();
  boolean isInitialized();
}
