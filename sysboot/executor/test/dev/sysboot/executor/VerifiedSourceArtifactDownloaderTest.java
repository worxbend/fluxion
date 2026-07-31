package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifiedSourceArtifactDownloaderTest {

  @TempDir Path tempDir;

  @Test
  void download_whenChecksumMatches_returnsExactResponseBytes() throws Exception {
    byte[] body = "trusted-key".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var downloader = new VerifiedSourceArtifactDownloader(client(body), tempDir);

    Path artifact = downloader.download(URI.create("https://example.test/key"), digest(body));

    assertThat(Files.readAllBytes(artifact)).isEqualTo(body);
  }

  @Test
  void download_whenChecksumMismatches_deletesUnverifiedArtifact() {
    var downloader = new VerifiedSourceArtifactDownloader(client("untrusted".getBytes()), tempDir);

    assertThatThrownBy(
            () ->
                downloader.download(
                    URI.create("https://example.test/key"), new Sha256Digest("0".repeat(64))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("SHA-256 mismatch");
    assertThat(tempDir).isEmptyDirectory();
  }

  @Test
  void download_whenArtifactExceedsSourceLimit_deletesUnverifiedArtifact() {
    BinaryDownloadClient oversizedClient =
        new BinaryDownloadClient() {
          @Override
          public void downloadToFile(URI url, Path destination) throws IOException {
            try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) {
              channel.position(VerifiedSourceArtifactDownloader.MAX_ARTIFACT_BYTES);
              channel.write(ByteBuffer.wrap(new byte[] {1}));
            }
          }

          @Override
          public String downloadText(URI url) {
            throw new UnsupportedOperationException();
          }
        };
    var downloader = new VerifiedSourceArtifactDownloader(oversizedClient, tempDir);

    assertThatThrownBy(
            () ->
                downloader.download(
                    URI.create("https://example.test/key"), new Sha256Digest("0".repeat(64))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("exceeds maximum size");
    assertThat(tempDir).isEmptyDirectory();
  }

  private BinaryDownloadClient client(byte[] body) {
    return new BinaryDownloadClient() {
      @Override
      public void downloadToFile(URI url, Path destination) throws IOException {
        Files.write(destination, body);
      }

      @Override
      public String downloadText(URI url) {
        throw new UnsupportedOperationException();
      }
    };
  }

  private Sha256Digest digest(byte[] body) throws Exception {
    return new Sha256Digest(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
  }
}
