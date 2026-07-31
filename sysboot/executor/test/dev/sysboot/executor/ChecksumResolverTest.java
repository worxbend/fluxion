package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChecksumResolverTest {

  @Test
  void parseSha256_whenSha256sumFormat_returnsDigest() throws Exception {
    String digest = "ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890";

    String parsed = ChecksumResolver.parseSha256(digest + "  fluxion.tar.gz\n", "fluxion.tar.gz");

    assertThat(parsed).isEqualTo(digest.toLowerCase());
  }

  @Test
  void parseSha256_whenNoDigest_throwsIOException() {
    assertThatThrownBy(() -> ChecksumResolver.parseSha256("not a checksum", "fluxion.tar.gz"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("SHA-256");
  }

  @Test
  void parseSha256_whenEntryNamesDifferentAsset_rejectsDigest() {
    String digest = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";

    assertThatThrownBy(() -> ChecksumResolver.parseSha256(digest + "  other.tar.gz\n", "rg.tar.gz"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("rg.tar.gz");
  }

  @Test
  void parseSha256_whenAssetEntryIsDuplicated_rejectsAmbiguousDigest() {
    String first = "a".repeat(64);
    String second = "b".repeat(64);

    assertThatThrownBy(
            () ->
                ChecksumResolver.parseSha256(
                    first + "  rg.tar.gz\n" + second + "  rg.tar.gz\n", "rg.tar.gz"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("duplicate");
  }

  @Test
  void parsesOfficialDotbotAndNerdFontsRelativeAssetNames() throws Exception {
    String digest = "a".repeat(64);

    assertThat(
            ChecksumResolver.parseSidecarSha256(
                digest + "  dist/dotbot-linux-amd64.tar.gz", "dotbot-linux-amd64.tar.gz"))
        .isEqualTo(digest);
    assertThat(
            ChecksumResolver.parseChecksumsFileSha256(
                digest + "  ./nerd-fonts-installer.tar.gz", "nerd-fonts-installer.tar.gz"))
        .isEqualTo(digest);
    assertThat(
            ChecksumResolver.parseSha256(
                digest + "  dist/compiled-binary.tar.gz", "compiled-binary.tar.gz"))
        .isEqualTo(digest);
  }

  @Test
  void rejectsUnsafeOrAmbiguousRelativeAssetNames() {
    String digest = "a".repeat(64);

    for (String unsafe :
        java.util.List.of(
            "../asset.tar.gz",
            "./../asset.tar.gz",
            "/asset.tar.gz",
            "dist\\asset.tar.gz",
            "dist//asset.tar.gz")) {
      assertThatThrownBy(
              () -> ChecksumResolver.parseSidecarSha256(digest + "  " + unsafe, "asset.tar.gz"))
          .isInstanceOf(ToolResolutionException.class)
          .hasMessageContaining("different asset");
    }
  }

  @Test
  void resolve_whenChecksumUrlPresent_downloadsAndParsesBoundDigest() throws Exception {
    String digest = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
    var resolver = new ChecksumResolver(new ChecksumDocumentClient(digest + "  rg\n"));

    assertThat(resolver.resolve(module()).orElseThrow().value()).isEqualTo(digest);
  }

  private static CompiledBinaryModule module() throws Exception {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(new URI("https://example.test/rg")),
        Optional.empty(),
        Optional.of(new BinaryUrl(new URI("https://example.test/rg.sha256"))),
        Path.of("/usr/local/bin/rg"),
        false);
  }

  private record ChecksumDocumentClient(String contents) implements BinaryDownloadClient {

    @Override
    public void downloadToFile(URI url, Path destination) {
      throw new UnsupportedOperationException("not used");
    }

    @Override
    public String downloadText(URI url) {
      return contents;
    }
  }
}
