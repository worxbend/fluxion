package dev.sysboot.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.sysboot.config.yaml.contract.ConfigDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.ConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class YamlConfigLoader implements ConfigLoader {

  private final ObjectMapper objectMapper;
  private final ConfigMapper configMapper;
  private final WorkstationProfileConfigMapper workstationProfileConfigMapper;

  public YamlConfigLoader() {
    this.objectMapper = new ObjectMapper(new YAMLFactory());
    this.objectMapper.findAndRegisterModules();
    this.configMapper = new ConfigMapper();
    this.workstationProfileConfigMapper = new WorkstationProfileConfigMapper();
  }

  @Override
  public BootstrapConfig load(Path configFile) {
    if (!Files.exists(configFile)) {
      throw new ConfigLoadException(configFile, "File does not exist");
    }
    if (!Files.isReadable(configFile)) {
      throw new ConfigLoadException(configFile, "File is not readable");
    }
    try {
      JsonNode root = objectMapper.readTree(configFile.toFile());
      return loadDetectedSchema(root, configFile.toAbsolutePath());
    } catch (IOException e) {
      throw new ConfigLoadException(configFile, "YAML parse error: " + e.getMessage(), e);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new ConfigLoadException(configFile, "Validation error: " + e.getMessage(), e);
    }
  }

  private BootstrapConfig loadDetectedSchema(JsonNode root, Path configFile) throws IOException {
    return switch (detectSchema(root)) {
      case LEGACY_FLUXION -> {
        ConfigDocument dto = objectMapper.treeToValue(root, ConfigDocument.class);
        yield configMapper.map(dto, configFile);
      }
      case WORKSTATION_PROFILE -> {
        WorkstationProfileDocument dto =
            objectMapper.treeToValue(root, WorkstationProfileDocument.class);
        yield workstationProfileConfigMapper.map(dto, configFile);
      }
    };
  }

  private ConfigSchema detectSchema(JsonNode root) {
    if (root == null || root.isMissingNode() || root.isNull()) {
      throw new IllegalArgumentException("Config file is empty");
    }
    if (!root.isObject()) {
      throw new IllegalArgumentException("Config root must be a YAML mapping");
    }
    if (hasAny(root, "apiVersion", "kind")) {
      return ConfigSchema.WORKSTATION_PROFILE;
    }
    if (hasAny(root, "profile", "os", "jobs", "phases", "modules", "schemaVersion")) {
      return ConfigSchema.LEGACY_FLUXION;
    }
    throw new IllegalArgumentException(
        "Unknown config schema; expected Fluxion profile/os/jobs/phases/modules fields or "
            + "apiVersion: initkit.io/v1alpha1 with kind: WorkstationProfile");
  }

  private boolean hasAny(JsonNode root, String... fieldNames) {
    for (String fieldName : fieldNames) {
      if (root.has(fieldName)) {
        return true;
      }
    }
    return false;
  }

  private enum ConfigSchema {
    LEGACY_FLUXION,
    WORKSTATION_PROFILE
  }
}
