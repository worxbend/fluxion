package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NerdFontModuleTest {

  private static final NerdFontConfig MUTABLE_CONFIG =
      new NerdFontConfig("latest", Path.of("/tmp/fonts"), true, List.of("JetBrainsMono"));

  @Test
  void inlineConfigRequiresPinnedRelease() {
    assertThatThrownBy(
            () ->
                new NerdFontModule(
                    new ModuleName("fonts"),
                    "v1.0.7",
                    "nerd-fonts-installer",
                    MUTABLE_CONFIG,
                    Optional.empty(),
                    Optional.empty()))
        .hasMessageContaining("pin an exact release");
  }

  @Test
  void installerVersionRequiresPinnedRelease() {
    assertThatThrownBy(
            () ->
                new NerdFontModule(
                    new ModuleName("fonts"),
                    "latest",
                    "nerd-fonts-installer",
                    new NerdFontConfig(
                        "v3.4.0", Path.of("/tmp/fonts"), true, List.of("JetBrainsMono")),
                    Optional.empty(),
                    Optional.empty()))
        .hasMessageContaining("installerVersion must pin an exact release");
  }

  @Test
  void externalConfigRemainsAnExplicitTrustBoundary() {
    assertThatCode(
            () ->
                new NerdFontModule(
                    new ModuleName("fonts"),
                    "v1.0.7",
                    "nerd-fonts-installer",
                    MUTABLE_CONFIG,
                    Optional.of(Path.of("/tmp/nerd-fonts.yaml")),
                    Optional.empty()))
        .doesNotThrowAnyException();
  }
}
