package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.AptRepositorySourceSetup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkstationSourceTrustTest {

  @TempDir Path tempDir;

  @Test
  void load_preservesDeclaredSourceChecksumInDomain() throws IOException {
    var config =
        load(aptSource("https://example.test/repo", "https://example.test/key", checksum()));

    var source = (AptRepositorySourceSetup) config.sourceSetups().getFirst();

    assertThat(source.artifactSha256())
        .hasValueSatisfying(digest -> assertThat(digest.value()).isEqualTo("a".repeat(64)));
  }

  @Test
  void load_rejectsHttpAndCredentialedAptUrisIncludingOptionSyntax() {
    assertRejected(
        aptSource("http://example.test/repo", "https://example.test/key", checksum()),
        "spec.sources.apt[0].spec.source");
    assertRejected(
        aptSource("https://user:secret@example.test/repo", "https://example.test/key", checksum()),
        "spec.sources.apt[0].spec.source");
    assertRejected(
        aptSource("https://example.test/repo", "https://user:secret@example.test/key", checksum()),
        "spec.sources.apt[0].spec.signingKeyUrl");
  }

  @Test
  void load_acceptsDebSrcWithSpacedOptionsAndHttpsUri() throws IOException {
    String source =
        """
        source: deb-src [arch=amd64 signed-by=/etc/apt/keyrings/example.gpg] https://example.test/repo stable main
        signingKeyUrl: https://example.test/key
        %s
        """
            .formatted(checksum());

    assertThat(load(source).sourceSetups()).hasSize(1);
  }

  @Test
  void load_rejectsMissingChecksumForRemoteTrustArtifact() {
    assertRejected(
        aptSource("https://example.test/repo", "https://example.test/key", ""),
        "spec.sources.apt[0].spec.checksum is required");
  }

  @Test
  void load_rejectsEnabledUnsignedRpmAndPacmanSourcesAtTheirYamlPaths() {
    assertRejectedSource(
        "dnf",
        """
        id: example
        baseUrl: https://example.test/repo
        gpgCheck: false
        """,
        "spec.sources.dnf[0].spec.gpgCheck must be true");
    assertRejectedSource(
        "pacman",
        """
        server: https://example.test/$repo/$arch
        config: /etc/pacman.conf
        sigLevel: Required TrustAll
        """,
        "spec.sources.pacman[0].spec.sigLevel");
  }

  private dev.sysboot.core.BootstrapConfig load(String sourceSpec) throws IOException {
    Path config = tempDir.resolve("profile-" + System.nanoTime() + ".yaml");
    Files.writeString(config, profile(sourceSpec));
    return new YamlConfigLoader().load(config);
  }

  private void assertRejected(String sourceSpec, String message) {
    assertThatThrownBy(() -> load(sourceSpec))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining(message);
  }

  private void assertRejectedSource(String kind, String sourceSpec, String message) {
    assertThatThrownBy(() -> loadSource(kind, sourceSpec))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining(message);
  }

  private dev.sysboot.core.BootstrapConfig loadSource(String kind, String sourceSpec)
      throws IOException {
    Path config = tempDir.resolve("source-" + System.nanoTime() + ".yaml");
    Files.writeString(
        config,
        """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: source-trust-test
        spec:
          target:
            os:
              distribution: fedora
          sources:
            %s:
              - name: example
                spec:
        %s
          plan:
            - name: packages
              kind: dnf-packages
              spec:
                packages: [curl]
        """
            .formatted(kind, sourceSpec.indent(10)));
    return new YamlConfigLoader().load(config);
  }

  private String aptSource(String repositoryUrl, String keyUrl, String checksum) {
    return """
    source: deb [arch=amd64 signed-by=/etc/apt/keyrings/example.gpg] %s stable main
    signingKeyUrl: %s
    %s
    """
        .formatted(repositoryUrl, keyUrl, checksum);
  }

  private String checksum() {
    return """
    checksum:
      algorithm: sha256
      value: %s
    """
        .formatted("a".repeat(64));
  }

  private String profile(String sourceSpec) {
    return """
    apiVersion: initkit.io/v1alpha1
    kind: WorkstationProfile
    metadata:
      name: source-trust-test
    spec:
      target:
        os:
          distribution: ubuntu
      sources:
        apt:
          - name: example
            spec:
    %s
      plan:
        - name: packages
          kind: apt-packages
          spec:
            packages: [curl]
    """
        .formatted(sourceSpec.indent(10));
  }
}
