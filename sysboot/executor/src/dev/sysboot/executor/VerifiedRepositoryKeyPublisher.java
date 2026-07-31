package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

final class VerifiedRepositoryKeyPublisher {

  private static final String MODE = "0644";
  private static final long MAX_KEY_BYTES = 16L * 1024L * 1024L;

  private VerifiedRepositoryKeyPublisher() {}

  static ProcessResult publish(
      Path source,
      Sha256Digest sourceDigest,
      Path destination,
      PrivilegedArtifactPublisher publisher)
      throws IOException {
    return publisher.consumeVerified(
        source,
        destination,
        MODE,
        sourceDigest,
        rootOwnedSource -> publishDecoded(rootOwnedSource, destination, publisher));
  }

  private static ProcessResult publishDecoded(
      Path rootOwnedSource, Path destination, PrivilegedArtifactPublisher publisher)
      throws IOException {
    byte[] decoded = OpenPgpKeyDecoder.decode(rootOwnedSource, MAX_KEY_BYTES);
    Path temporary =
        Files.createTempFile(
            "sysboot-repository-key-",
            ".gpg",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    try {
      Files.write(temporary, decoded);
      return publisher.publish(temporary, destination, MODE, ArtifactDigests.sha256(decoded));
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
