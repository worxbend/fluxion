package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompiledBinaryInstallerTest {

  @TempDir private Path tempDir;

  @Test
  void install_whenDirectBinaryDownloaded_writesModeAndSymlink() throws Exception {
    byte[] body = "#!/bin/sh\necho rg\n".getBytes();
    Path installPath = tempDir.resolve("bin/rg");
    Path symlink = tempDir.resolve("bin/ripgrep");
    Files.createDirectories(installPath.getParent());
    var installer = installer(Map.of(directUri(), body));

    StepResult result = installer.install(module(directUri(), installPath, sha256(body), symlink));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(Files.readAllBytes(installPath)).isEqualTo(body);
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(installPath);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(installPath)))
        .isEqualTo("rwxr-x---");
  }

  @Test
  void install_whenTarGzDownloaded_extractsSelectedPathWithStripComponents() throws Exception {
    byte[] body = "archive-rg".getBytes();
    byte[] archive = tarGz("ripgrep-1.0/bin/rg", body);
    Path installPath = tempDir.resolve("rg");
    var installer = installer(Map.of(archiveUri(), archive));
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(archiveUri()),
            Optional.of(new Checksum("sha256", sha256(archive))),
            Optional.empty(),
            Optional.empty(),
            installPath,
            Optional.of("bin/rg"),
            1,
            Optional.of("0755"),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty());

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(Files.readAllBytes(installPath)).isEqualTo(body);
  }

  @Test
  void install_whenChecksumMismatches_failsBeforeDestinationWrite() throws Exception {
    Path installPath = tempDir.resolve("rg");
    var installer = installer(Map.of(directUri(), "bad".getBytes()));

    StepResult result =
        installer.install(module(directUri(), installPath, sha256("good".getBytes()), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("Checksum mismatch");
    assertThat(installPath).doesNotExist();
  }

  private CompiledBinaryInstaller installer(Map<URI, byte[]> downloads) {
    return new CompiledBinaryInstaller(new NoopRunner(), new FakeDownloadClient(downloads), new DefaultBinaryFileSystem());
  }

  private CompiledBinaryModule module(
      URI uri, Path installPath, String checksum, Path symlinkPath) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(uri),
        Optional.of(new Checksum("sha256", checksum)),
        Optional.empty(),
        Optional.empty(),
        installPath,
        Optional.empty(),
        0,
        Optional.of("0750"),
        Optional.ofNullable(symlinkPath),
        false,
        Optional.empty(),
        Optional.empty());
  }

  private byte[] tarGz(String entryName, byte[] body) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GzipCompressorOutputStream(output);
        var tar = new TarArchiveOutputStream(gzip)) {
      var entry = new TarArchiveEntry(entryName);
      entry.setSize(body.length);
      tar.putArchiveEntry(entry);
      tar.write(body);
      tar.closeArchiveEntry();
    }
    return output.toByteArray();
  }

  private static URI archiveUri() {
    return URI.create("https://example.test/rg.tar.gz");
  }

  private static URI directUri() {
    return URI.create("https://example.test/rg");
  }

  private static String sha256(byte[] body) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
    return HexFormat.of().formatHex(digest);
  }

  private record FakeDownloadClient(Map<URI, byte[]> downloads) implements BinaryDownloadClient {
    @Override
    public void downloadToFile(URI url, Path destination) throws IOException {
      Files.write(destination, downloads.get(url));
    }

    @Override
    public String downloadText(URI url) {
      throw new UnsupportedOperationException("not used");
    }
  }

  private record NoopRunner() implements ShellRunner {
    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
  }
}
