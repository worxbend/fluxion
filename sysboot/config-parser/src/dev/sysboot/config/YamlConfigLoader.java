package dev.sysboot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.sysboot.config.dto.ConfigDto;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.ConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class YamlConfigLoader implements ConfigLoader {

  private final ObjectMapper objectMapper;
  private final ConfigMapper configMapper;

  public YamlConfigLoader() {
    this.objectMapper = new ObjectMapper(new YAMLFactory());
    this.objectMapper.findAndRegisterModules();
    this.configMapper = new ConfigMapper();
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
      ConfigDto dto = objectMapper.readValue(configFile.toFile(), ConfigDto.class);
      return configMapper.map(dto, configFile.toAbsolutePath());
    } catch (IOException e) {
      throw new ConfigLoadException(configFile, "YAML parse error: " + e.getMessage(), e);
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new ConfigLoadException(configFile, "Validation error: " + e.getMessage(), e);
    }
  }
}
