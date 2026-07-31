package dev.sysboot.executor;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

final class VerifiedSourceArtifactDownloader implements SourceArtifactDownloadClient {

  static final long MAX_ARTIFACT_BYTES = 16L * 1024L * 1024L;
  private static final java.util.Set<java.nio.file.attribute.PosixFilePermission>
      PRIVATE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

  private final BinaryDownloadClient downloadClient;
  private final Path tempDirectory;

  VerifiedSourceArtifactDownloader() {
    this(
        new HttpBinaryDownloadClient(MAX_ARTIFACT_BYTES),
        Path.of(System.getProperty("java.io.tmpdir")));
  }

  VerifiedSourceArtifactDownloader(BinaryDownloadClient downloadClient, Path tempDirectory) {
    this.downloadClient = Objects.requireNonNull(downloadClient);
    this.tempDirectory = Objects.requireNonNull(tempDirectory);
  }

  @Override
  public Path download(URI url, Sha256Digest sha256) throws IOException {
    Path artifact =
        Files.createTempFile(
            tempDirectory,
            "sysboot-source-",
            ".artifact",
            PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
    boolean verified = false;
    Throwable primaryFailure = null;
    try {
      downloadClient.downloadToFile(url, artifact);
      if (Files.size(artifact) > MAX_ARTIFACT_BYTES) {
        throw new IOException(
            "Source artifact exceeds maximum size of " + MAX_ARTIFACT_BYTES + " bytes");
      }
      verify(artifact, sha256);
      Files.setPosixFilePermissions(artifact, PRIVATE_PERMISSIONS);
      verified = true;
      return artifact;
    } catch (IOException | RuntimeException e) {
      primaryFailure = e;
      throw e;
    } finally {
      if (!verified) {
        FailurePreservingCleanup.run(primaryFailure, () -> Files.deleteIfExists(artifact));
      }
    }
  }

  private void verify(Path artifact, Sha256Digest expected) throws IOException {
    String actual = HexFormat.of().formatHex(digest(artifact));
    if (!actual.equals(expected.value())) {
      throw new IOException("Source artifact SHA-256 mismatch");
    }
  }

  private byte[] digest(Path artifact) throws IOException {
    try (var input = Files.newInputStream(artifact)) {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
