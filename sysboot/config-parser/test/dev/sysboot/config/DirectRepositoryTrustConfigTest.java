package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.ZypperRepositoryModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectRepositoryTrustConfigTest {

  @TempDir Path tempDirectory;

  @Test
  void load_remoteArtifactsWithoutChecksum_rejectsEveryDirectSourceKind() {
    assertRejected(
        """
        - type: apt-repository
          name: apt
          source: deb [signed-by=/etc/apt/keyrings/apt.gpg] https://example.test/debian stable main
          signingKeyUrl: https://example.test/apt.key
        """,
        "checksum");
    assertRejected(
        """
        - type: rpm-repository
          name: rpm
          baseUrl: https://example.test/rpm
          gpgKeyUrl: https://example.test/rpm.key
        """,
        "checksum");
    assertRejected(
        """
        - type: flatpak-remote
          name: flathub
          remote: flathub
          url: https://example.test/flathub.flatpakrepo
        """,
        "checksum");
  }

  @Test
  void load_checksumWithoutRemoteArtifact_rejectsUnboundDigest() {
    assertRejected(
        """
        - type: apt-repository
          name: apt
          source: deb [signed-by=/etc/apt/keyrings/apt.gpg] https://example.test/debian stable main
          keyring: /etc/apt/keyrings/apt.gpg
          checksum:
            algorithm: sha256
            value: %s
        """
            .formatted("a".repeat(64)),
        "configured together");
  }

  @Test
  void load_insecureOrCredentialedRepositoryUrls_rejectsAtDomainBoundary() {
    assertRejected(
        """
        - type: pacman-repository
          name: pacman
          server: http://example.test/$repo/$arch
        """,
        "HTTPS");
    assertRejected(
        """
        - type: rpm-repository
          name: rpm
          baseUrl: https://user:secret@example.test/rpm
          gpgCheck: false
        """,
        "without user-info");
  }

  @Test
  void load_enabledRepositoryCannotDisableSignatureVerification() {
    assertRejected(
        """
        - type: rpm-repository
          name: rpm
          baseUrl: https://example.test/rpm
          gpgCheck: false
        """,
        "must enforce gpgCheck");
  }

  @Test
  void load_workstationZypperRepository_requiresHttpsKeyAndBoundChecksum() throws IOException {
    assertThatThrownBy(() -> loadWorkstationZypper("", "https://example.test/key"))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("gpgKeyUrl and checksum must be configured together");
    assertThatThrownBy(
            () -> loadWorkstationZypper(checksum(), "https://user:secret@example.test/key"))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("must not include user-info");

    var config = loadWorkstationZypper(checksum(), "https://example.test/key");
    var module = (ZypperRepositoryModule) config.phases().getFirst().modules().getFirst();
    assertThat(module.artifactSha256()).hasValue(new dev.sysboot.core.Sha256Digest("a".repeat(64)));
  }

  @Test
  void load_workstationZypperRepository_rejectsDisabledSignatureCheckAtYamlPath() {
    assertThatThrownBy(
            () ->
                loadWorkstationZypperTrust(
                    """
                    gpgCheck: false
                    """))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.plan[0].spec.gpgCheck must be true");
  }

  private void assertRejected(String steps, String message) {
    assertThatThrownBy(() -> load(steps))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining(message);
  }

  private dev.sysboot.core.BootstrapConfig load(String steps) throws IOException {
    Path config = tempDirectory.resolve("profile-" + System.nanoTime() + ".yaml");
    Files.writeString(config, profile(steps));
    return new YamlConfigLoader().load(config);
  }

  private dev.sysboot.core.BootstrapConfig loadWorkstationZypper(String checksum, String keyUrl)
      throws IOException {
    return loadWorkstationZypperTrust(
        """
        gpgKeyUrl: %s
        %s
        """
            .formatted(keyUrl, checksum.indent(0)));
  }

  private dev.sysboot.core.BootstrapConfig loadWorkstationZypperTrust(String repositoryTrust)
      throws IOException {
    Path config = tempDirectory.resolve("workstation-" + System.nanoTime() + ".yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: source-trust
        spec:
          target:
            os:
              distribution: opensuse
          plan:
            - name: repository
              kind: zypper-repository
              spec:
                baseUrl: https://example.test/repo
        %s
        """
            .formatted(repositoryTrust.indent(8)));
    return new YamlConfigLoader().load(config);
  }

  private String checksum() {
    return """
    checksum:
      algorithm: sha256
      value: %s
    """
        .formatted("a".repeat(64));
  }

  private String profile(String steps) {
    return """
    profile: source-trust
    os:
      type: fedora
      release: "44"
    jobs:
      - name: repositories
        steps:
    %s
    """
        .formatted(steps.indent(8));
  }
}
