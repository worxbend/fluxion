package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepositoryTrustContractTest {

  private static final Sha256Digest DIGEST = new Sha256Digest("a".repeat(64));

  @Test
  void aptRemoteKey_requiresChecksumAndConvertsWithoutLosingTrustBinding() {
    assertThatThrownBy(
            () ->
                new AptRepositoryModule(
                    new ModuleName("example"),
                    "deb [signed-by=/etc/apt/keyrings/example.gpg]"
                        + " https://example.test/repo stable main",
                    Path.of("/etc/apt/sources.list.d/example.list"),
                    Optional.of(URI.create("https://example.test/key")),
                    Optional.of(Path.of("/etc/apt/keyrings/example.gpg"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checksum");

    AptRepositoryModule module = aptWithChecksum();

    assertThat(module.asSourceSetup().artifactSha256()).contains(DIGEST);
  }

  @Test
  void rpmAndZypperRemoteKeys_requireMatchingChecksumPresence() {
    assertThatThrownBy(
            () ->
                new RpmRepositoryModule(
                    new ModuleName("rpm"),
                    "rpm",
                    URI.create("https://example.test/repo"),
                    Path.of("/etc/yum.repos.d/example.repo"),
                    Optional.of(URI.create("https://example.test/key")),
                    true,
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checksum");
    assertThatThrownBy(
            () ->
                new ZypperRepositoryModule(
                    new ModuleName("zypper"),
                    "zypper",
                    URI.create("https://example.test/repo"),
                    Path.of("/etc/zypp/repos.d/example.repo"),
                    Optional.empty(),
                    true,
                    false,
                    true,
                    Optional.of(DIGEST)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("configured together");
  }

  @Test
  void enabledRpmAndZypperRepositories_cannotDisableSignatureVerification() {
    assertThatThrownBy(
            () ->
                new RpmRepositoryModule(
                    new ModuleName("rpm"),
                    "rpm",
                    URI.create("https://example.test/repo"),
                    Path.of("/etc/yum.repos.d/example.repo"),
                    Optional.empty(),
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must enforce gpgCheck");
    assertThatThrownBy(
            () ->
                new ZypperRepositorySourceSetup(
                    new ModuleName("zypper"),
                    "zypper",
                    URI.create("https://example.test/repo"),
                    Path.of("/etc/zypp/repos.d/example.repo"),
                    Optional.empty(),
                    true,
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must enforce gpgCheck");
  }

  @Test
  void enabledPacmanRepository_requiresEffectiveSignedTrustedPolicyForBothScopes() {
    for (String unsafe :
        java.util.List.of(
            "Never TrustedOnly",
            "Required TrustAll",
            "Required TrustedOnly DatabaseOptional",
            "Required TrustedOnly PackageTrustAll",
            "Required TrustedOnly DatabaseRequired DatabaseTrustAll")) {
      assertThatThrownBy(
              () ->
                  new PacmanRepositoryModule(
                      new ModuleName("example"),
                      "example",
                      URI.create("https://example.test/$repo/$arch"),
                      Path.of("/etc/pacman.conf"),
                      Optional.of(unsafe),
                      Optional.empty(),
                      true))
          .as(unsafe)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("signed, trusted");
    }

    var safe =
        new PacmanRepositoryModule(
            new ModuleName("example"),
            "example",
            URI.create("https://example.test/$repo/$arch"),
            Path.of("/etc/pacman.conf"),
            Optional.of(
                "Never TrustAll Required TrustedOnly"
                    + " DatabaseOptional DatabaseRequired"
                    + " PackageTrustAll PackageTrustedOnly"),
            Optional.empty(),
            true);

    assertThat(safe.sigLevel())
        .contains(
            "Never TrustAll Required TrustedOnly"
                + " DatabaseOptional DatabaseRequired"
                + " PackageTrustAll PackageTrustedOnly");
  }

  @Test
  void flatpakAlwaysRequiresDescriptorChecksumAndHttps() {
    assertThatThrownBy(
            () ->
                new FlatpakRemoteModule(
                    new ModuleName("flathub"),
                    "flathub",
                    URI.create("https://example.test/flathub.flatpakrepo"),
                    true,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checksum");
    assertThatThrownBy(
            () ->
                new FlatpakRemoteModule(
                    new ModuleName("flathub"),
                    "flathub",
                    URI.create("http://example.test/flathub.flatpakrepo"),
                    true,
                    Optional.of(DIGEST)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS");
  }

  @Test
  void pacmanServer_requiresHttpsWithoutUserInfo() {
    assertThatThrownBy(
            () ->
                new PacmanRepositoryModule(
                    new ModuleName("example"),
                    "example",
                    URI.create("https://user:secret@example.test/$repo/$arch"),
                    Path.of("/etc/pacman.conf"),
                    Optional.empty(),
                    Optional.empty(),
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS without user-info");
  }

  private AptRepositoryModule aptWithChecksum() {
    return new AptRepositoryModule(
        new ModuleName("example"),
        "deb [signed-by=/etc/apt/keyrings/example.gpg]" + " https://example.test/repo stable main",
        Path.of("/etc/apt/sources.list.d/example.list"),
        Optional.of(URI.create("https://example.test/key")),
        Optional.of(Path.of("/etc/apt/keyrings/example.gpg")),
        Optional.of(DIGEST));
  }
}
