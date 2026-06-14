package dev.sysboot.core;

import java.nio.file.Path;

public interface ConfigLoader {
  BootstrapConfig load(Path configFile);
}
