package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.HostPlatform.Architecture;
import dev.sysboot.core.HostPlatform.OperatingSystem;
import org.junit.jupiter.api.Test;

/**
 * Asset naming is the one place where a silent typo produces a 404 on a user's machine and nowhere
 * else, so every known tool's naming is pinned here.
 */
class ToolSpecTest {

  private static final HostPlatform LINUX_AMD64 =
      new HostPlatform(OperatingSystem.LINUX, Architecture.AMD64);
  private static final HostPlatform LINUX_ARM64 =
      new HostPlatform(OperatingSystem.LINUX, Architecture.ARM64);
  private static final HostPlatform MACOS_ARM64 =
      new HostPlatform(OperatingSystem.MACOS, Architecture.ARM64);

  @Test
  void nerdFontsInstallerUsesVersionedGoStyleAssetNames() {
    assertThat(KnownTools.NERD_FONTS_INSTALLER.assetName(LINUX_AMD64))
        .isEqualTo("nerd-fonts-installer_v1.0.7_linux_amd64.tar.gz");
    assertThat(KnownTools.NERD_FONTS_INSTALLER.assetName(MACOS_ARM64))
        .isEqualTo("nerd-fonts-installer_v1.0.7_darwin_arm64.tar.gz");
  }

  @Test
  void nerdFontsInstallerStillResolvesReleasesFromBeforeTheAssetRename() {
    // v1.0.0 through v1.0.6 publish `nerdfont-install_<tag>_<os>_<arch>.tar.gz`; v1.0.7 renamed it.
    // A profile that pins an older version must keep working.
    assertThat(KnownTools.NERD_FONTS_INSTALLER.withVersion("v1.0.5").assetNames(LINUX_AMD64))
        .containsExactly(
            "nerd-fonts-installer_v1.0.5_linux_amd64.tar.gz",
            "nerdfont-install_v1.0.5_linux_amd64.tar.gz");
  }

  @Test
  void nerdFontsInstallerPointsAtThePluralRepository() {
    assertThat(KnownTools.NERD_FONTS_INSTALLER.repository())
        .isEqualTo("worxbend/nerd-fonts-installer");
    assertThat(KnownTools.NERD_FONTS_INSTALLER.executableName()).isEqualTo("nerd-fonts-installer");
  }

  @Test
  void dotbotUsesUnversionedHyphenatedAssetNames() {
    assertThat(KnownTools.DOTBOT_GO.assetName(LINUX_AMD64)).isEqualTo("dotbot-linux-amd64.tar.gz");
    assertThat(KnownTools.DOTBOT_GO.assetName(LINUX_ARM64)).isEqualTo("dotbot-linux-arm64.tar.gz");
  }

  @Test
  void binstallerSpellsMacOsAsMacosNotDarwin() {
    assertThat(KnownTools.BINSTALLER.assetName(LINUX_AMD64))
        .isEqualTo("binstaller-v0.2.0-linux-amd64.tar.gz");
    assertThat(KnownTools.BINSTALLER.assetName(MACOS_ARM64))
        .isEqualTo("binstaller-v0.2.0-macos-arm64.tar.gz");
  }

  @Test
  void assetUrlCombinesRepositoryTagAndAssetName() {
    assertThat(KnownTools.DOTBOT_GO.assetUrl(LINUX_AMD64))
        .isEqualTo(
            "https://github.com/worxbend/dotbot-go/releases/download/v0.4.2/dotbot-linux-amd64.tar.gz");
  }

  @Test
  void versionCanBePinnedWithoutTouchingTheOtherFields() {
    ToolSpec pinned = KnownTools.NERD_FONTS_INSTALLER.withVersion("latest");

    assertThat(pinned.isLatest()).isTrue();
    assertThat(pinned.assetName(LINUX_AMD64))
        .isEqualTo("nerd-fonts-installer_latest_linux_amd64.tar.gz");
    assertThat(pinned.repository()).isEqualTo(KnownTools.NERD_FONTS_INSTALLER.repository());
  }

  @Test
  void platformDetectionNormalisesCommonSystemPropertyValues() {
    assertThat(HostPlatform.detectOperatingSystem("Mac OS X")).isEqualTo(OperatingSystem.MACOS);
    assertThat(HostPlatform.detectOperatingSystem("Linux")).isEqualTo(OperatingSystem.LINUX);
    assertThat(HostPlatform.detectArchitecture("aarch64")).isEqualTo(Architecture.ARM64);
    assertThat(HostPlatform.detectArchitecture("amd64")).isEqualTo(Architecture.AMD64);
    assertThat(HostPlatform.detectArchitecture("x86_64")).isEqualTo(Architecture.AMD64);
  }
}
