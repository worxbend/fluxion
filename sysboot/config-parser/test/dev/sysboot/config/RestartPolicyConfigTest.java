package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestartPolicyConfigTest {

  @Test
  void load_whenRequiresNewShellIsUnsupported_rejectsIt(@TempDir Path directory) throws Exception {
    Path config = directory.resolve("unsupported-shell.yaml");
    Files.writeString(
        config,
        """
        profile: unsupported-shell
        os:
          type: fedora
          release: "44"
        jobs:
          - name: shell
            restartPolicy:
              type: requires-new-shell
              shell: fish
            steps:
              - type: manual
                name: placeholder
                message: placeholder
        """);

    assertThatThrownBy(() -> new YamlConfigLoader().load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("Unsupported requires-new-shell value: fish");
  }
}
