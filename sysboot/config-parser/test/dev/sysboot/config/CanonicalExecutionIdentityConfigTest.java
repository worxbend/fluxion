package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanonicalExecutionIdentityConfigTest {

  @TempDir Path tempDir;

  @Test
  void duplicateGpgKeyIdentityIsRejectedDuringParsing() throws Exception {
    assertRejected(
        """
        - name: repository-keys
          kind: gpg-key
          spec:
            keys:
              - url: https://example.test/first.asc
                fingerprint: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
              - url: https://example.test/second.asc
                fingerprint: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
        """,
        "duplicate canonical key identities");
  }

  @Test
  void duplicateFileWriteDestinationIsRejectedDuringParsing() throws Exception {
    assertRejected(
        """
        - name: files
          kind: file-writes
          spec:
            files:
              - name: first
                destination: /tmp/fluxion/config
                content: first
              - name: second
                destination: /tmp/fluxion/config
                content: second
        """,
        "duplicate canonical destinations");
  }

  @Test
  void duplicateSdkmanPackageIsRejectedDuringParsing() throws Exception {
    assertRejected(
        """
        - name: sdks
          kind: sdkman-packages
          spec:
            packages:
              - candidate: java
                version: 25-tem
              - candidate: " java "
                version: " 25-tem "
        """,
        "duplicate canonical packages");
  }

  private void assertRejected(String plan, String message) throws Exception {
    Path config = tempDir.resolve("duplicate.yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: duplicate-identities
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
        %s
        """
            .formatted(plan.indent(4)));

    assertThatThrownBy(() -> new YamlConfigLoader().load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining(message);
  }
}
