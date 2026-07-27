package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.BootstrapConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The {@code binstaller-profile} step is parsed by both config frontends. */
class BinstallerProfileParsingTest {

  private final YamlConfigLoader loader = new YamlConfigLoader();

  @Test
  void jobsAndStepsSchemaParsesABinstallerProfileStep(@TempDir Path tmpDir) throws IOException {
    Path config =
        write(
            tmpDir,
            """
            profile: laptop
            os:
              type: fedora
              release: "44"
            jobs:
              - name: binaries
                steps:
                  - type: binstaller-profile
                    name: developer-binaries
                    config: ~/.config/binstaller/config.yaml
                    only: [yazi, neovim]
                    skip: [zig]
                    locked: true
                    lockFile: ~/binstaller.lock.json
            """);

    BinstallerModule module = onlyBinstallerModule(loader.load(config));

    assertThat(module.name().value()).isEqualTo("developer-binaries");
    assertThat(module.config().toString()).endsWith("/.config/binstaller/config.yaml");
    assertThat(module.config().isAbsolute()).as("~ is expanded at parse time").isTrue();
    assertThat(module.only()).containsExactly("yazi", "neovim");
    assertThat(module.skip()).containsExactly("zig");
    assertThat(module.locked()).isTrue();
    assertThat(module.lockFile()).isPresent();
  }

  @Test
  void workstationProfileManifestParsesABinstallerProfileEntry(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        write(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: laptop
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: developer-binaries
                  kind: binstaller-profile
                  spec:
                    config: ~/.config/binstaller/config.yaml
                    only: [yazi]
            """);

    BinstallerModule module = onlyBinstallerModule(loader.load(config));

    assertThat(module.name().value()).isEqualTo("developer-binaries");
    assertThat(module.only()).containsExactly("yazi");
    assertThat(module.locked()).isFalse();
  }

  @Test
  void defaultsComeFromTheKnownBinstallerRelease(@TempDir Path tmpDir) throws IOException {
    Path config =
        write(
            tmpDir,
            """
            profile: laptop
            os:
              type: arch
            jobs:
              - name: binaries
                steps:
                  - type: binstaller-profile
                    name: developer-binaries
                    config: /etc/binstaller.yaml
            """);

    BinstallerModule module = onlyBinstallerModule(loader.load(config));

    assertThat(module.binstallerBinary()).isEqualTo("binstaller");
    assertThat(module.installerVersion())
        .isEqualTo(dev.sysboot.core.KnownTools.BINSTALLER.version());
  }

  @Test
  void aLockedManifestEntryWithoutALockFileIsRejected(@TempDir Path tmpDir) throws IOException {
    Path config =
        write(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: laptop
            spec:
              plan:
                - name: developer-binaries
                  kind: binstaller-profile
                  spec:
                    config: /etc/binstaller.yaml
                    locked: true
            """);

    assertThatThrownBy(() -> loader.load(config)).hasMessageContaining("lockFile");
  }

  @Test
  void anInlineConfigObjectIsRejectedBecauseBinstallerOwnsItsOwnSchema(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        write(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: laptop
            spec:
              plan:
                - name: developer-binaries
                  kind: binstaller-profile
                  spec:
                    config:
                      apiVersion: binstaller.io/v1alpha1
            """);

    assertThatThrownBy(() -> loader.load(config))
        .hasMessageContaining("must be a path to a BinaryDistributionProfile");
  }

  private BinstallerModule onlyBinstallerModule(BootstrapConfig config) {
    return config.phases().stream()
        .flatMap(phase -> phase.modules().stream())
        .filter(BinstallerModule.class::isInstance)
        .map(BinstallerModule.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no binstaller-profile module was parsed"));
  }

  private Path write(Path dir, String content) throws IOException {
    Path file = dir.resolve("profile.yaml");
    Files.writeString(file, content);
    return file;
  }
}
