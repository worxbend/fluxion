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
          Optional.empty(),
          java.util.Map.of(
              "dotbot-linux-amd64.tar.gz",
                  "a7229b8d098454ffeb2858ddcf1b63602dfc7be06e08b57c39d839c08f9dbd01",
              "dotbot-linux-arm64.tar.gz",
                  "21e94e915de43f2cbe086973437ec6a5f81e46ddbc5280707165c0ebb6090b45",
              "dotbot-darwin-amd64.tar.gz",
                  "89c22f14929dcb11cc8d1c81086d5d0d9336f89438c6326d00aea7420ea8043c",
              "dotbot-darwin-arm64.tar.gz",
                  "f7b970f1b325175b0a16c278050502e45b22541c343f6d4082638197d99dddc4"));

  /** Linux-only Dotfiles backend, native Scala with the same directives as {@link #DOTBOT_GO}. */
  public static final ToolSpec DOTBOT_SCALA =
      new ToolSpec(
          "dotbot",
          "worxbend/dotbot-scala",
          "v0.1.0",
          "dotbot-${os}-${arch}.tar.gz",
          ToolSpec.OsNaming.GO,
          ToolSpec.ChecksumPolicy.SIDECAR_SHA256,
          Optional.empty(),
          java.util.Map.of(
              "dotbot-linux-amd64.tar.gz",
                  "388f49ab380ddbde153b1fa8ce361237d92e0add0d96df8ef1052093c3b0c673",
              "dotbot-linux-arm64.tar.gz",
                  "ed87fad6adaee20c63bb9a821f005402c754b713d5e3089909b58ed59e9753e9"));

  /**
   * Nerd Fonts: <a href="https://github.com/worxbend/nerd-fonts-installer">
   * worxbend/nerd-fonts-installer</a>.
   */
  public static final ToolSpec NERD_FONTS_INSTALLER =
      new ToolSpec(
          "nerd-fonts-installer",
          "worxbend/nerd-fonts-installer",
          "v1.0.7",
          "nerd-fonts-installer_${version}_${os}_${arch}.tar.gz",
          ToolSpec.OsNaming.GO,
          ToolSpec.ChecksumPolicy.CHECKSUMS_FILE,
          Optional.empty(),
          java.util.Map.of(
              "nerd-fonts-installer_v1.0.7_linux_amd64.tar.gz",
                  "0903de2304b07035794546256cbfbfe117a04c12d1e9ae92c544e8a9ee7bd8b2",
              "nerd-fonts-installer_v1.0.7_linux_arm64.tar.gz",
                  "49b30cf173b6a5465dcc7271ae19b5dddf083ba360cc51063121773ad3da6517",
              "nerd-fonts-installer_v1.0.7_darwin_amd64.tar.gz",
                  "da47b8301f326b001988caf1fe6a0537fac3f18528b6c3c801a7e14045a70004",
              "nerd-fonts-installer_v1.0.7_darwin_arm64.tar.gz",
                  "4c1bbcd01d9d5984d4ad225be9208e7653b4055254c2f55d89b681fd348aeb07"));

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
          Optional.empty(),
          java.util.Map.of(
              "binstaller-v0.2.0-linux-amd64.tar.gz",
                  "802bf5da1f6af5f0f00984751f45cb5c0448ee24283729ff21e5ea7f0718f951",
              "binstaller-v0.2.0-linux-arm64.tar.gz",
                  "48135498e3973347b6c0f0b843942def56f5dbba22c95d8f75d5272186a74d52",
              "binstaller-v0.2.0-macos-amd64.tar.gz",
                  "f54abc96c8bd7270145ecbca2a69767e23a1a4bb616802c767972b2220055dba",
              "binstaller-v0.2.0-macos-arm64.tar.gz",
                  "b735fb63bf628302a9b01caa6b589f61785a53d28e219c225699db6771ef6645"));

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
