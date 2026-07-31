package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RepositoryDestinationPolicyTest {

  private static final ModuleName NAME = new ModuleName("vendor");
  private static final URI REPOSITORY = URI.create("https://example.test/repository");
  private static final String APT_SOURCE =
      "deb [signed-by=/etc/apt/keyrings/vendor.gpg]"
          + " https://example.test/repository stable main";

  @Test
  void constructorsNormalizeConfinedRepositoryDestinations() {
    var apt =
        new AptRepositoryModule(
            NAME,
            APT_SOURCE,
            Path.of("/etc/apt/sources.list.d/nested/../vendor.list"),
            Optional.empty(),
            Optional.of(Path.of("/etc/apt/keyrings/nested/../vendor.gpg")));
    var rpm = rpmModule(Path.of("/etc/yum.repos.d/nested/../vendor.repo"));
    var zypper = zypperModule(Path.of("/etc/zypp/repos.d/nested/../vendor.repo"), false);
    var pacman =
        pacmanModule(
            Path.of("/etc/pacman.conf"),
            Optional.of(Path.of("/etc/pacman.d/nested/../vendor-mirrorlist")));
    var key =
        new GpgKeyModule.GpgKey(
            "https://example.test/key",
            Optional.of(Path.of("/etc/pki/rpm-gpg/nested/../RPM-GPG-KEY-vendor")),
            "A".repeat(40));

    assertThat(apt.sourceListPath()).isEqualTo(Path.of("/etc/apt/sources.list.d/vendor.list"));
    assertThat(apt.keyringPath()).contains(Path.of("/etc/apt/keyrings/vendor.gpg"));
    assertThat(rpm.repoFilePath()).isEqualTo(Path.of("/etc/yum.repos.d/vendor.repo"));
    assertThat(zypper.repoFilePath()).isEqualTo(Path.of("/etc/zypp/repos.d/vendor.repo"));
    assertThat(pacman.include()).contains(Path.of("/etc/pacman.d/vendor-mirrorlist"));
    assertThat(key.keyring()).contains(Path.of("/etc/pki/rpm-gpg/RPM-GPG-KEY-vendor"));
    assertThat(zypper.asSourceSetup().autoRefresh()).isFalse();
  }

  @Test
  void directModuleConstructorsRejectWrongRootsExtensionsAndNormalizationEscapes() {
    List<ThrowingCallable> constructors =
        List.of(
            () ->
                new AptRepositoryModule(
                    NAME,
                    APT_SOURCE,
                    Path.of("/etc/sudoers"),
                    Optional.empty(),
                    Optional.of(Path.of("/etc/apt/keyrings/vendor.gpg"))),
            () ->
                new AptRepositoryModule(
                    NAME,
                    "deb [signed-by=/etc/sudoers]" + " https://example.test/repository stable main",
                    Path.of("/etc/apt/sources.list.d/vendor.list"),
                    Optional.empty(),
                    Optional.of(Path.of("/etc/apt/keyrings/../../sudoers"))),
            () -> rpmModule(Path.of("/etc/yum.repos.d/vendor.conf")),
            () -> zypperModule(Path.of("/etc/zypp/repos.d/../../sudoers.repo"), true),
            () -> pacmanModule(Path.of("/etc/sudoers"), Optional.empty()),
            () ->
                new GpgKeyModule.GpgKey(
                    "https://example.test/key",
                    Optional.of(Path.of("/etc/apt/keyrings/vendor.key")),
                    "A".repeat(40)));

    constructors.forEach(
        constructor ->
            assertThatThrownBy(constructor)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("null"));
  }

  @Test
  void sourceSetupConstructorsRejectUnsafeDestinationsBeforeExecution() {
    List<ThrowingCallable> constructors =
        List.of(
            () ->
                new AptRepositorySourceSetup(
                    NAME,
                    APT_SOURCE,
                    Path.of("/etc/apt/sources.list.d/../../sudoers"),
                    Optional.empty(),
                    Optional.of(Path.of("/etc/apt/keyrings/vendor.gpg"))),
            () ->
                new RpmRepositorySourceSetup(
                    NAME,
                    "vendor",
                    REPOSITORY,
                    Path.of("/etc/sudoers"),
                    Optional.empty(),
                    true,
                    false),
            () ->
                new ZypperRepositorySourceSetup(
                    NAME,
                    "vendor",
                    REPOSITORY,
                    Path.of("/etc/zypp/repos.d/vendor.list"),
                    Optional.empty(),
                    true,
                    false),
            () ->
                new PacmanRepositorySourceSetup(
                    NAME,
                    "vendor",
                    REPOSITORY,
                    Path.of("/etc/pacman.conf"),
                    Optional.empty(),
                    Optional.of(Path.of("/etc/pacman.d/../../sudoers")),
                    true));

    constructors.forEach(
        constructor ->
            assertThatThrownBy(constructor).isInstanceOf(IllegalArgumentException.class));
  }

  private RpmRepositoryModule rpmModule(Path destination) {
    return new RpmRepositoryModule(
        NAME, "vendor", REPOSITORY, destination, Optional.empty(), false, false);
  }

  private ZypperRepositoryModule zypperModule(Path destination, boolean autoRefresh) {
    return new ZypperRepositoryModule(
        NAME, "vendor", REPOSITORY, destination, Optional.empty(), false, false, autoRefresh);
  }

  private PacmanRepositoryModule pacmanModule(Path config, Optional<Path> include) {
    return new PacmanRepositoryModule(
        NAME, "vendor", REPOSITORY, config, Optional.of("Required TrustedOnly"), include, true);
  }
}
