package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryDestinationConfigTest {

  @TempDir Path tempDirectory;

  @Test
  void legacyFrontendRejectsRepositoryDestinationsOutsideManagerDirectories() {
    assertRejected(
        """
        profile: unsafe-destination
        os:
          type: debian
          release: "13"
        jobs:
          - name: repositories
            steps:
              - type: apt-repository
                name: vendor
                source: deb [signed-by=/etc/apt/keyrings/vendor.gpg] https://example.test/repository stable main
                sourceList: /etc/apt/sources.list.d/../../sudoers
                keyring: /etc/apt/keyrings/vendor.gpg
        """,
        "APT source-list path");
    assertRejected(
        """
        profile: unsafe-destination
        os:
          type: fedora
          release: "44"
        jobs:
          - name: repositories
            steps:
              - type: rpm-repository
                name: vendor
                baseUrl: https://example.test/repository
                repoFile: /etc/yum.repos.d/vendor.conf
                gpgCheck: false
        """,
        "extension");
  }

  @Test
  void workstationFrontendRejectsSourceAndPlanDestinationsBeforeExecution() {
    assertRejected(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: unsafe-source
        spec:
          target:
            os:
              distribution: ubuntu
          sources:
            apt:
              - name: vendor
                spec:
                  source: deb [signed-by=/etc/apt/keyrings/vendor.gpg] https://example.test/repository stable main
                  sourceList: /etc/sudoers
                  keyring: /etc/apt/keyrings/vendor.gpg
          plan:
            - name: packages
              kind: apt-packages
              spec:
                packages: [curl]
        """,
        "APT source-list path");
    assertRejected(
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: unsafe-keyring
        spec:
          target:
            os:
              distribution: fedora
          plan:
            - name: vendor-key
              kind: gpg-key
              spec:
                keys:
                  - url: https://example.test/key
                    keyring: /etc/sudoers
                    fingerprint: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA
        """,
        "GPG keyring path");
  }

  @Test
  void workstationZypperSourcePreservesDisabledAutoRefresh() throws IOException {
    var config =
        load(
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: zypper-auto-refresh
            spec:
              target:
                os:
                  distribution: opensuse
              sources:
                zypper:
                  - name: vendor
                    spec:
                      id: vendor
                      baseUrl: https://example.test/repository
                      repoFile: /etc/zypp/repos.d/vendor.repo
                      enabled: false
                      gpgCheck: false
                      autoRefresh: false
              plan:
                - name: packages
                  kind: zypper-packages
                  spec:
                    packages: [curl]
            """);

    var source = (ZypperRepositorySourceSetup) config.sourceSetups().getFirst();

    assertThat(source.autoRefresh()).isFalse();
  }

  private void assertRejected(String yaml, String message) {
    assertThatThrownBy(() -> load(yaml))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining(message);
  }

  private dev.sysboot.core.BootstrapConfig load(String yaml) throws IOException {
    Path config = tempDirectory.resolve("profile-" + System.nanoTime() + ".yaml");
    Files.writeString(config, yaml);
    return new YamlConfigLoader().load(config);
  }
}
