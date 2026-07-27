package dev.sysboot.core;

import java.util.List;
import java.util.Optional;

/**
 * Release-asset conventions for the external tools Fluxion delegates to.
 *
 * <p>Each entry is verified against the tool's published releases. Getting an asset template wrong
 * is invisible until a real machine tries to install something, so {@code ToolCatalogContractTest}
 * checks these against the live releases when a network is available.
 */
public final class KnownTools {

  /** Dotfiles: <a href="https://github.com/worxbend/dotbot-go">worxbend/dotbot-go</a>. */
  public static final ToolSpec DOTBOT_GO =
      new ToolSpec(
          "dotbot",
          "worxbend/dotbot-go",
          "v0.4.2",
          "dotbot-${os}-${arch}.tar.gz",
          ToolSpec.OsNaming.GO,
          ToolSpec.ChecksumPolicy.SIDECAR_SHA256,
          Optional.empty());

  /** Dotfiles, native Scala backend with the same directives as {@link #DOTBOT_GO}. */
  public static final ToolSpec DOTBOT_SCALA =
      new ToolSpec(
          "dotbot",
          "worxbend/dotbot-scala",
          "v0.1.0",
          "dotbot-${os}-${arch}.tar.gz",
          ToolSpec.OsNaming.GO,
          ToolSpec.ChecksumPolicy.SIDECAR_SHA256,
          Optional.empty());

  /**
   * Nerd Fonts: <a href="https://github.com/worxbend/nerd-fonts-installer">
   * worxbend/nerd-fonts-installer</a>.
   */
  public static final ToolSpec NERD_FONTS_INSTALLER =
      new ToolSpec(
          "nerd-fonts-installer",
          "worxbend/nerd-fonts-installer",
          "v1.0.7",
          // The project renamed both the binary and its assets at v1.0.7. Releases up to v1.0.6 are
          // still published under the old name, so a pinned older version has to keep resolving.
          List.of(
              "nerd-fonts-installer_${version}_${os}_${arch}.tar.gz",
              "nerdfont-install_${version}_${os}_${arch}.tar.gz"),
          ToolSpec.OsNaming.GO,
          ToolSpec.ChecksumPolicy.CHECKSUMS_FILE,
          Optional.empty());

  /**
   * Binary distributions: <a href="https://github.com/worxbend/binstaller">worxbend/binstaller</a>.
   */
  public static final ToolSpec BINSTALLER =
      new ToolSpec(
          "binstaller",
          "worxbend/binstaller",
          "v0.2.0",
          "binstaller-${version}-${os}-${arch}.tar.gz",
          ToolSpec.OsNaming.MACOS,
          ToolSpec.ChecksumPolicy.SIDECAR_SHA256,
          Optional.empty());

  private static final List<ToolSpec> ALL =
      List.of(DOTBOT_GO, DOTBOT_SCALA, NERD_FONTS_INSTALLER, BINSTALLER);

  private KnownTools() {}

  public static List<ToolSpec> all() {
    return ALL;
  }

  public static Optional<ToolSpec> byRepository(String repository) {
    return ALL.stream().filter(spec -> spec.repository().equals(repository)).findFirst();
  }
}
