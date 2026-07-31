package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.HostPlatform.Architecture;
import dev.sysboot.core.HostPlatform.OperatingSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
  void nerdFontsInstallerDoesNotOfferAnAbsentLegacyCandidateAtThePinnedRelease() {
    assertThat(KnownTools.NERD_FONTS_INSTALLER.assetNames(LINUX_AMD64))
        .containsExactly("nerd-fonts-installer_v1.0.7_linux_amd64.tar.gz");
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
  void versionMustBePresentInTheTrustedDigestCatalog() {
    assertThatThrownBy(() -> KnownTools.NERD_FONTS_INSTALLER.withVersion("latest"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trusted release-digest catalog");
    assertThat(KnownTools.NERD_FONTS_INSTALLER.withVersion("v1.0.7"))
        .isEqualTo(KnownTools.NERD_FONTS_INSTALLER);
  }

  @ParameterizedTest
  @ValueSource(strings = {"latest", "v9.9.9"})
  void unversionedDotbotAssetsStillRejectUncataloguedReleaseTags(String version) {
    assertThatThrownBy(() -> KnownTools.DOTBOT_GO.withVersion(version))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trusted release-digest catalog");
    assertThatThrownBy(() -> KnownTools.DOTBOT_SCALA.withVersion(version))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trusted release-digest catalog");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"/tmp/escape", ".", "..", "../escape", "nested/version", "nested\\version"})
  void versionRejectsAbsoluteDotAndTraversalSegments(String version) {
    assertThatThrownBy(() -> KnownTools.DOTBOT_GO.withVersion(version))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cache-path segment");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/tmp/tool", ".", "..", "../tool", "nested/tool", "nested\\tool"})
  void toolNameAndBinaryNameRejectCachePathEscapes(String value) {
    assertThatThrownBy(
            () ->
                new ToolSpec(
                    value,
                    KnownTools.DOTBOT_GO.repository(),
                    "v1.0.0",
                    "tool-${os}-${arch}.tar.gz",
                    ToolSpec.OsNaming.GO,
                    ToolSpec.ChecksumPolicy.SIDECAR_SHA256,
                    java.util.Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> KnownTools.DOTBOT_GO.withBinaryName(value))
        .isInstanceOf(IllegalArgumentException.class);
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
