package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.HostPlatform;
import dev.sysboot.core.HostPlatform.Architecture;
import dev.sysboot.core.HostPlatform.OperatingSystem;
import dev.sysboot.core.KnownTools;
import dev.sysboot.core.ToolSpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Release candidates remain pinned to the versions represented in the digest catalog. */
class ToolBrokerFallbackTest {

  private static final HostPlatform LINUX_AMD64 =
      new HostPlatform(OperatingSystem.LINUX, Architecture.AMD64);

  @TempDir Path tempDir;

  @Test
  void rejectsLegacyVersionWithoutCataloguedDigests() {
    assertThatThrownBy(() -> KnownTools.NERD_FONTS_INSTALLER.withVersion("v1.0.5"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trusted release-digest catalog");
  }

  @Test
  void rejectsUnknownVersionBeforeResolution() {
    assertThatThrownBy(() -> KnownTools.NERD_FONTS_INSTALLER.withVersion("v9.9.9"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("trusted release-digest catalog");
  }

  @Test
  void namesTheArchiveContentsWhenTheExpectedExecutableIsMissing() throws Exception {
    byte[] archive = tarGz("dotbot-go", "payload");
    ToolSpec spec =
        KnownTools.DOTBOT_GO.withAssetSha256(
            KnownTools.DOTBOT_GO.assetName(LINUX_AMD64), sha256(archive));
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(spec.assetUrl(LINUX_AMD64) + ".sha256", sha256(archive));

    var broker =
        new ToolBroker(downloads, cache(spec), LINUX_AMD64, name -> java.util.Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("It contains: dotbot-go");
  }

  private ToolCache cache(ToolSpec spec) {
    return new ToolCache(tempDir.resolve("cache-" + spec.version()));
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  private static byte[] tarGz(String entryName, String content) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GzipCompressorOutputStream(bytes);
        var tar = new TarArchiveOutputStream(gzip)) {
      byte[] payload = content.getBytes(StandardCharsets.UTF_8);
      var entry = new TarArchiveEntry(entryName);
      entry.setSize(payload.length);
      tar.putArchiveEntry(entry);
      tar.write(payload);
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private static final class FakeDownloadClient implements BinaryDownloadClient {

    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();
    private final List<String> fileRequests = new ArrayList<>();

    @Override
    public void downloadToFile(URI url, Path destination) throws IOException {
      fileRequests.add(url.toString());
      byte[] content = files.get(url.toString());
      if (content == null) {
        throw new IOException("HTTP 404");
      }
      Files.write(destination, content);
    }

    @Override
    public String downloadText(URI url) throws IOException {
      String content = texts.get(url.toString());
      if (content == null) {
        throw new IOException("HTTP 404 for " + url);
      }
      return content;
    }
  }
}
