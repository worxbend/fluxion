package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalItemDuplicateConfigTest {

  @Test
  void duplicateGitConfigMapKeysAreRejectedDuringYamlParsing(@TempDir Path directory)
      throws Exception {
    Path config = directory.resolve("duplicate.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: duplicate-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: git
              kind: git-config
              spec:
                scope: global
                entries:
                  user.name: first
                  user.name: second
        """);

    assertThatThrownBy(() -> new YamlConfigLoader().load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("Duplicate field 'user.name'");
  }
}
