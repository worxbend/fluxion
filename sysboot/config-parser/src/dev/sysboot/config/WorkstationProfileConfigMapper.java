package dev.sysboot.config;

import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.BootstrapConfig;
import java.nio.file.Path;

final class WorkstationProfileConfigMapper {

  BootstrapConfig map(WorkstationProfileDocument document, Path configFile) {
    throw new IllegalStateException(
        "WorkstationProfile manifest mapping is not implemented yet: " + configFile);
  }
}
