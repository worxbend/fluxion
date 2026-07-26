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
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolBrokerTest {

  private static final HostPlatform LINUX_AMD64 =
      new HostPlatform(OperatingSystem.LINUX, Architecture.AMD64);

  @TempDir Path tempDir;

  @Test
  void prefersAToolTheUserAlreadyHasOnPath() {
    Path onPath = tempDir.resolve("dotbot");
    var broker =
        new ToolBroker(new FakeDownloadClient(), cache(), LINUX_AMD64, name -> Optional.of(onPath));

    assertThat(broker.resolve(KnownTools.DOTBOT_GO)).isEqualTo(onPath);
    assertThat(broker.describe(KnownTools.DOTBOT_GO).source()).isEqualTo(ToolBroker.Source.PATH);
  }

  @Test
  void reusesAPreviouslyCachedDownloadInsteadOfFetchingAgain() throws Exception {
    ToolCache cache = cache();
    Path cached = cache.executable(KnownTools.DOTBOT_GO);
    Files.createDirectories(cached.getParent());
    Files.writeString(cached, "#!/bin/sh\n");
    cached.toFile().setExecutable(true);

    var downloads = new FakeDownloadClient();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    assertThat(broker.resolve(KnownTools.DOTBOT_GO)).isEqualTo(cached);
    assertThat(downloads.fileRequests).isEmpty();
    assertThat(broker.describe(KnownTools.DOTBOT_GO).source()).isEqualTo(ToolBroker.Source.CACHE);
  }

  @Test
  void downloadsVerifiesAndCachesWhenTheToolIsMissing() throws Exception {
    ToolSpec spec = KnownTools.DOTBOT_GO;
    byte[] archive = tarGz("dotbot", "#!/bin/sh\necho dotbot\n");
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(
        spec.assetUrl(LINUX_AMD64) + ".sha256",
        sha256(archive) + "  " + spec.assetName(LINUX_AMD64));

    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    Path resolved = broker.resolve(spec);

    assertThat(resolved).isEqualTo(cache.executable(spec));
    assertThat(Files.readString(resolved)).contains("echo dotbot");
    assertThat(Files.isExecutable(resolved)).isTrue();
  }

  @Test
  void readsChecksumsFromAChecksumsFileWhenThatIsWhatTheReleasePublishes() throws Exception {
    ToolSpec spec = KnownTools.NERD_FONTS_INSTALLER;
    byte[] archive = tarGz("nerd-fonts-installer", "#!/bin/sh\n");
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(
        spec.releaseDownloadBase() + "/checksums.txt",
        """
        0000000000000000000000000000000000000000000000000000000000000000  other-asset.tar.gz
        %s  %s
        """
            .formatted(sha256(archive), spec.assetName(LINUX_AMD64)));

    var broker = new ToolBroker(downloads, cache(), LINUX_AMD64, name -> Optional.empty());

    assertThat(broker.resolve(spec)).exists();
  }

  @Test
  void refusesToInstallWhenTheChecksumDoesNotMatch() throws Exception {
    ToolSpec spec = KnownTools.DOTBOT_GO;
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), tarGz("dotbot", "payload"));
    downloads.texts.put(
        spec.assetUrl(LINUX_AMD64) + ".sha256",
        "0000000000000000000000000000000000000000000000000000000000000000");

    var broker = new ToolBroker(downloads, cache(), LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("Checksum mismatch");
  }

  @Test
  void reportsAMissingExecutableInsideTheArchive() throws Exception {
    ToolSpec spec = KnownTools.DOTBOT_GO;
    byte[] archive = tarGz("something-else", "payload");
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(spec.assetUrl(LINUX_AMD64) + ".sha256", sha256(archive));

    var broker = new ToolBroker(downloads, cache(), LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("does not contain an executable named 'dotbot'");
  }

  @Test
  void findsExecutablesNestedInsideAnArchiveDirectory() throws Exception {
    ToolSpec spec = KnownTools.NERD_FONTS_INSTALLER;
    byte[] archive =
        tarGz("nerd-fonts-installer_v1.0.7_linux_amd64/nerd-fonts-installer", "#!/bin/sh\n");
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(
        spec.releaseDownloadBase() + "/checksums.txt",
        sha256(archive) + "  " + spec.assetName(LINUX_AMD64));

    var broker = new ToolBroker(downloads, cache(), LINUX_AMD64, name -> Optional.empty());

    assertThat(broker.resolve(spec)).exists();
  }

  private ToolCache cache() {
    return new ToolCache(tempDir.resolve("cache"));
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
        throw new IOException("HTTP 404 for " + url);
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
