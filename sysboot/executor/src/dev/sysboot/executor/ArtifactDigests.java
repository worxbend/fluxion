package dev.sysboot.executor;

import dev.sysboot.core.Sha256Digest;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ArtifactDigests {

  private static final long MAX_ARTIFACT_BYTES = 32L * 1024L * 1024L;

  private ArtifactDigests() {}

  static Sha256Digest sha256(byte[] content) {
    return new Sha256Digest(HexFormat.of().formatHex(messageDigest().digest(content)));
  }

  static Sha256Digest sha256(Path path) throws IOException {
    return sha256(path, MAX_ARTIFACT_BYTES);
  }

  static Sha256Digest sha256(Path path, long maximumBytes) throws IOException {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Verified artifact stage is not a regular non-symlink file");
    }
    if (maximumBytes <= 0 || Files.size(path) > maximumBytes) {
      throw new IOException("Verified artifact stage exceeds the maximum size");
    }
    MessageDigest digest = messageDigest();
    try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
      byte[] buffer = new byte[8192];
      for (int read; (read = input.read(buffer)) >= 0; ) {
        digest.update(buffer, 0, read);
      }
    }
    return new Sha256Digest(HexFormat.of().formatHex(digest.digest()));
  }

  private static MessageDigest messageDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
