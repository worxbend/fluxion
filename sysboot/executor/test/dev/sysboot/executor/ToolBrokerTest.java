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
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolBrokerTest {

  private static final HostPlatform LINUX_AMD64 =
      new HostPlatform(OperatingSystem.LINUX, Architecture.AMD64);
  private static final HostPlatform MACOS_AMD64 =
      new HostPlatform(OperatingSystem.MACOS, Architecture.AMD64);

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
    byte[] archive = tarGz("dotbot", "#!/bin/sh\necho dotbot\n");
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
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
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(resolved)))
        .isEqualTo("rwx------");
    try (var entries = Files.list(resolved.getParent())) {
      assertThat(entries.map(path -> path.getFileName().toString()).toList())
          .noneMatch(name -> name.endsWith(".part"));
    }
  }

  @Test
  void readsChecksumsFromAChecksumsFileWhenThatIsWhatTheReleasePublishes() throws Exception {
    byte[] archive = tarGz("nerd-fonts-installer", "#!/bin/sh\n");
    ToolSpec spec = trusted(KnownTools.NERD_FONTS_INSTALLER, archive);
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
    ToolSpec spec =
        KnownTools.DOTBOT_GO.withAssetSha256(
            KnownTools.DOTBOT_GO.assetName(LINUX_AMD64), "0".repeat(64));
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
  void attackerArchiveAndMatchingSidecarCannotOverrideCataloguedDigest() throws Exception {
    ToolSpec spec = KnownTools.DOTBOT_GO;
    byte[] attackerArchive = tarGz("dotbot", "#!/bin/sh\necho compromised\n");
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), attackerArchive);
    downloads.texts.put(
        spec.assetUrl(LINUX_AMD64) + ".sha256",
        sha256(attackerArchive) + "  dist/" + spec.assetName(LINUX_AMD64));
    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("disagrees with the trusted release-digest catalog");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void refusesToInstallWhenTheChecksumEntryIsMissing() throws Exception {
    byte[] archive = tarGz("nerd-fonts-installer", "payload");
    ToolSpec spec = trusted(KnownTools.NERD_FONTS_INSTALLER, archive);
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(
        spec.releaseDownloadBase() + "/checksums.txt", sha256(archive) + "  another-asset.tar.gz");
    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("checksum entry is missing");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void refusesToInstallWhenTheChecksumSidecarIsEmpty() throws Exception {
    byte[] archive = tarGz("dotbot", "payload");
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(spec.assetUrl(LINUX_AMD64) + ".sha256", " \n");
    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("checksum sidecar is empty");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void refusesToInstallWhenTheChecksumDigestIsMalformed() throws Exception {
    byte[] archive = tarGz("dotbot", "payload");
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(spec.assetUrl(LINUX_AMD64) + ".sha256", "not-a-sha256");
    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("SHA-256 digest is malformed");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void refusesToInstallWhenTheChecksumNamesAnotherAsset() throws Exception {
    byte[] archive = tarGz("dotbot", "payload");
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(
        spec.assetUrl(LINUX_AMD64) + ".sha256", sha256(archive) + "  another-asset.tar.gz");
    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("checksum entry names a different asset");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void refusesToInstallWhenTheAssetHasNoCataloguedDigestBeforeNetwork() throws Exception {
    ToolSpec spec =
        new ToolSpec(
            "unverified",
            "example/unverified",
            "v1.0.0",
            "unverified-${os}-${arch}.tar.gz",
            ToolSpec.OsNaming.GO,
            ToolSpec.ChecksumPolicy.NONE,
            Optional.empty());
    byte[] archive = tarGz("unverified", "payload");
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("trusted release-digest catalog");
    assertThat(cache.executable(spec)).doesNotExist();
    assertThat(downloads.fileRequests).isEmpty();
    assertThat(downloads.textRequests).isEmpty();
  }

  @Test
  void refusesDotbotScalaOnUncataloguedMacOsBeforeNetwork() {
    var downloads = new FakeDownloadClient();
    var broker = new ToolBroker(downloads, cache(), MACOS_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(KnownTools.DOTBOT_SCALA))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("trusted release-digest catalog");
    assertThat(downloads.fileRequests).isEmpty();
    assertThat(downloads.textRequests).isEmpty();
  }

  @Test
  void refusesToInstallWhenTheChecksumEntryIsDuplicated() throws Exception {
    byte[] archive = tarGz("nerd-fonts-installer", "payload");
    ToolSpec spec = trusted(KnownTools.NERD_FONTS_INSTALLER, archive);
    String entry = sha256(archive) + "  " + spec.assetName(LINUX_AMD64);
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(spec.releaseDownloadBase() + "/checksums.txt", entry + "\n" + entry);
    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    assertThatThrownBy(() -> broker.resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("checksum entry is duplicated");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void reportsAMissingExecutableInsideTheArchive() throws Exception {
    byte[] archive = tarGz("something-else", "payload");
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
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
    byte[] archive =
        tarGz("nerd-fonts-installer_v1.0.7_linux_amd64/nerd-fonts-installer", "#!/bin/sh\n");
    ToolSpec spec = trusted(KnownTools.NERD_FONTS_INSTALLER, archive);
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(
        spec.releaseDownloadBase() + "/checksums.txt",
        sha256(archive) + "  " + spec.assetName(LINUX_AMD64));

    var broker = new ToolBroker(downloads, cache(), LINUX_AMD64, name -> Optional.empty());

    assertThat(broker.resolve(spec)).exists();
  }

  @Test
  void rejectsDuplicateExecutableEntriesWithoutPublishingEither() throws Exception {
    byte[] archive =
        tarGz(
            List.of(
                new ArchiveTestEntry("first/dotbot", "first", false),
                new ArchiveTestEntry("second/dotbot", "second", false)));
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
    var downloads = downloads(spec, archive);
    ToolCache cache = cache();

    assertThatThrownBy(
            () ->
                new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty())
                    .resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("more than one executable");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void rejectsSymlinkExecutableEntryWithoutPublishingIt() throws Exception {
    byte[] archive = tarGz(List.of(new ArchiveTestEntry("dotbot", "elsewhere", true)));
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
    var downloads = downloads(spec, archive);
    ToolCache cache = cache();

    assertThatThrownBy(
            () ->
                new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty())
                    .resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("non-regular");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void rejectsExecutableLargerThanExtractionLimit() throws Exception {
    byte[] archive = oversizedTarGz("dotbot", ToolArchivePublisher.MAX_EXECUTABLE_BYTES + 1);
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
    var downloads = downloads(spec, archive);
    ToolCache cache = cache();

    assertThatThrownBy(
            () ->
                new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty())
                    .resolve(spec))
        .isInstanceOf(ToolResolutionException.class)
        .hasMessageContaining("extraction limit");
    assertThat(cache.executable(spec)).doesNotExist();
  }

  @Test
  void concurrentBrokersPublishOneVerifiedToolUnderThePerToolLock() throws Exception {
    byte[] archive = tarGz("dotbot", "#!/bin/sh\n");
    ToolSpec spec = trusted(KnownTools.DOTBOT_GO, archive);
    var downloads = downloads(spec, archive);
    ToolCache cache = cache();
    var first = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());
    var second = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    try (var tasks = Executors.newFixedThreadPool(2)) {
      var results = tasks.invokeAll(List.of(() -> first.resolve(spec), () -> second.resolve(spec)));

      assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
    }
    assertThat(downloads.fileRequests).hasSize(1);
    assertThat(Files.isExecutable(cache.executable(spec))).isTrue();
  }

  @Test
  void managedBinstallerIgnoresAnArbitraryPathExecutable() throws Exception {
    byte[] archive = tarGz("binstaller", "#!/bin/sh\necho managed\n");
    ToolSpec spec = trusted(KnownTools.BINSTALLER, archive);
    var downloads = downloads(spec, archive);
    ToolCache cache = cache();
    Path fakePath = tempDir.resolve("fake-path/binstaller");
    Files.createDirectories(fakePath.getParent());
    Files.writeString(fakePath, "#!/bin/sh\necho fake\n");
    fakePath.toFile().setExecutable(true);
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.of(fakePath));

    assertThat(broker.describe(spec).source()).isEqualTo(ToolBroker.Source.DOWNLOAD);
    assertThat(broker.resolve(spec)).isEqualTo(cache.executable(spec));
    assertThat(Files.readString(cache.executable(spec))).contains("managed");
    assertThat(downloads.fileRequests).hasSize(1);
  }

  @Test
  void managedBinstallerReverifiesItsCachedExecutableBeforeEveryUse() throws Exception {
    byte[] archive = tarGz("binstaller", "#!/bin/sh\necho managed\n");
    ToolSpec spec = trusted(KnownTools.BINSTALLER, archive);
    var downloads = downloads(spec, archive);
    ToolCache cache = cache();
    var broker = new ToolBroker(downloads, cache, LINUX_AMD64, name -> Optional.empty());

    Path executable = broker.resolve(spec);
    Files.writeString(executable, "#!/bin/sh\necho replaced\n");
    executable.toFile().setExecutable(true);

    assertThat(broker.describe(spec).source()).isEqualTo(ToolBroker.Source.DOWNLOAD);
    assertThat(broker.resolve(spec)).isEqualTo(executable);
    assertThat(Files.readString(executable)).contains("managed");
    assertThat(downloads.fileRequests).hasSize(2);
  }

  private ToolCache cache() {
    return new ToolCache(tempDir.resolve("cache"));
  }

  private static String sha256(byte[] content) throws Exception {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }

  private static ToolSpec trusted(ToolSpec spec, byte[] archive) throws Exception {
    return spec.withAssetSha256(spec.assetName(LINUX_AMD64), sha256(archive));
  }

  private static byte[] tarGz(String entryName, String content) throws IOException {
    return tarGz(List.of(new ArchiveTestEntry(entryName, content, false)));
  }

  private static byte[] tarGz(List<ArchiveTestEntry> entries) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GzipCompressorOutputStream(bytes);
        var tar = new TarArchiveOutputStream(gzip)) {
      for (ArchiveTestEntry specification : entries) {
        byte[] payload = specification.content().getBytes(StandardCharsets.UTF_8);
        var entry =
            new TarArchiveEntry(
                specification.name(),
                specification.symlink() ? TarConstants.LF_SYMLINK : TarConstants.LF_NORMAL);
        if (specification.symlink()) {
          entry.setLinkName(specification.content());
          entry.setSize(0);
        } else {
          entry.setSize(payload.length);
        }
        tar.putArchiveEntry(entry);
        if (!specification.symlink()) {
          tar.write(payload);
        }
        tar.closeArchiveEntry();
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] oversizedTarGz(String entryName, long size) throws IOException {
    var bytes = new ByteArrayOutputStream();
    try (var gzip = new GzipCompressorOutputStream(bytes);
        var tar = new TarArchiveOutputStream(gzip)) {
      var entry = new TarArchiveEntry(entryName);
      entry.setSize(size);
      tar.putArchiveEntry(entry);
      byte[] zeros = new byte[8192];
      long remaining = size;
      while (remaining > 0) {
        int count = (int) Math.min(remaining, zeros.length);
        tar.write(zeros, 0, count);
        remaining -= count;
      }
      tar.closeArchiveEntry();
    }
    return bytes.toByteArray();
  }

  private FakeDownloadClient downloads(ToolSpec spec, byte[] archive) throws Exception {
    var downloads = new FakeDownloadClient();
    downloads.files.put(spec.assetUrl(LINUX_AMD64), archive);
    downloads.texts.put(
        spec.assetUrl(LINUX_AMD64) + ".sha256",
        sha256(archive) + "  " + spec.assetName(LINUX_AMD64));
    return downloads;
  }

  private record ArchiveTestEntry(String name, String content, boolean symlink) {}

  private static final class FakeDownloadClient implements BinaryDownloadClient {

    private final Map<String, byte[]> files = new HashMap<>();
    private final Map<String, String> texts = new HashMap<>();
    private final List<String> fileRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> textRequests = Collections.synchronizedList(new ArrayList<>());

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
      textRequests.add(url.toString());
      String content = texts.get(url.toString());
      if (content == null) {
        throw new IOException("HTTP 404 for " + url);
      }
      return content;
    }
  }
}
