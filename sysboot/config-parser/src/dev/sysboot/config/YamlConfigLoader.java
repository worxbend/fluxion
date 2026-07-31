package dev.sysboot.config;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.sysboot.config.yaml.contract.ConfigDocument;
import dev.sysboot.config.yaml.contract.WorkstationProfileDocument;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.ConfigLoader;
import dev.sysboot.core.HostFactsProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

public final class YamlConfigLoader implements ConfigLoader {

  static final long MAX_CONFIG_BYTES = 8L * 1024L * 1024L;

  private final ObjectMapper objectMapper;
  private final ConfigMapper configMapper;
  private final WorkstationProfileConfigMapper workstationProfileConfigMapper;
  private final WorkstationProfileInterpolator workstationProfileInterpolator;

  public YamlConfigLoader() {
    this(new JvmHostFactsProvider());
  }

  public YamlConfigLoader(HostFactsProvider hostFactsProvider) {
    this.objectMapper =
        new ObjectMapper(
            YAMLFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());
    this.objectMapper.findAndRegisterModules();
    this.configMapper = new ConfigMapper();
    this.workstationProfileConfigMapper = new WorkstationProfileConfigMapper(hostFactsProvider);
    this.workstationProfileInterpolator = new WorkstationProfileInterpolator();
  }

  @Override
  public BootstrapConfig load(Path configFile) {
    if (!Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new ConfigLoadException(configFile, "File does not exist");
    }
    try {
      requireSafeConfigFile(configFile);
      JsonNode root;
      try (InputStream input =
          Files.newInputStream(configFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
        root = objectMapper.readTree(input);
      }
      return loadDetectedSchema(root, configFile.toAbsolutePath());
    } catch (IOException e) {
      throw new ConfigLoadException(configFile, "YAML parse error: " + e.getMessage(), e);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new ConfigLoadException(configFile, "Validation error: " + e.getMessage(), e);
    }
  }

  private void requireSafeConfigFile(Path configFile) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(configFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
      throw new IOException("Config must be a regular non-symbolic file");
    }
    if (attributes.size() > MAX_CONFIG_BYTES) {
      throw new IOException("Config exceeds maximum size of " + MAX_CONFIG_BYTES + " bytes");
    }
    if (!Files.isReadable(configFile)) {
      throw new IOException("Config file is not readable");
    }
  }

  private BootstrapConfig loadDetectedSchema(JsonNode root, Path configFile) throws IOException {
    return switch (detectSchema(root)) {
      case LEGACY_FLUXION -> {
        ConfigDocument dto = objectMapper.treeToValue(root, ConfigDocument.class);
        yield configMapper.map(dto, configFile);
      }
      case WORKSTATION_PROFILE -> {
        JsonNode interpolatedRoot = workstationProfileInterpolator.interpolate(root);
        WorkstationProfileDocument dto =
            objectMapper.treeToValue(interpolatedRoot, WorkstationProfileDocument.class);
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
