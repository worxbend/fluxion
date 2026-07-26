package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * Upstream projects rename their release assets. Pinning an older version has to keep working, and
 * a genuinely missing asset has to fail with a message that says what was tried.
 */
class ToolBrokerFallbackTest {

  private static final HostPlatform LINUX_AMD64 =
      new HostPlatform(OperatingSystem.LINUX, Architecture.AMD64);

  @TempDir Path tempDir;

  @Test
  void fallsBackToTheLegacyAssetNameWhenThePreferredOneIsAbsent() throws Exception {
    ToolSpec spec =
        KnownTools.NERD_FONTS_INSTALLER.withVersion("v1.0.5").withBinaryName("nerdfont-install");
    byte[] archive = tarGz("nerdfont-install_v1.0.5_linux_amd64/nerdfont-install", "#!/bin/sh\n");

    var downloads = new FakeDownloadClient();
    // Only the legacy asset exists at this tag, exactly as upstream publishes it.
    downloads.files.put(spec.assetUrl("nerdfont-install_v1.0.5_linux_amd64.tar.gz"), archive);
    downloads.texts.put(
        spec.releaseDownloadBase() + "/checksums.txt",
        sha256(archive) + "  nerdfont-install_v1.0.5_linux_amd64.tar.gz");

    var broker =
        new ToolBroker(downloads, cache(spec), LINUX_AMD64, name -> java.util.Optional.empty());

    assertThat(broker.resolve(spec)).exists();
    assertThat(downloads.fileRequests)
        .as("the current asset name is tried first, then the legacy one")
        .containsExactly(
            spec.assetUrl("nerd-fonts-installer_v1.0.5_linux_amd64.tar.gz"),
            spec.assetUrl("nerdfont-install_v1.0.5_linux_amd64.tar.gz"));
  }

  @Test
  void reportsEveryAssetItTriedWhenNoneResolve() {
    ToolSpec spec = KnownTools.NERD_FONTS_INSTALLER.withVersion("v9.9.9");
    var broker =
        new ToolBroker(
            new FakeDownloadClient(), cache(spec), LINUX_AMD64, name -> java.util.Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("nerd-fonts-installer_v9.9.9_linux_amd64.tar.gz")
        .hasMessageContaining("nerdfont-install_v9.9.9_linux_amd64.tar.gz");
  }

  @Test
  void namesTheArchiveContentsWhenTheExpectedExecutableIsMissing() throws Exception {
    ToolSpec spec = KnownTools.DOTBOT_GO;
    byte[] archive = tarGz("dotbot-go", "payload");
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
