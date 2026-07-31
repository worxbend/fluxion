package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.nio.file.Path;

interface PrivilegedArtifactPublisher {

  ProcessResult publish(Path source, Path destination, String mode, Sha256Digest expected)
      throws IOException;

  ProcessResult consumeVerified(
      Path source, Path stagingAnchor, String mode, Sha256Digest expected, StagedConsumer consumer)
      throws IOException;

  ProcessResult consume(Path source, Path stagingAnchor, String mode, StagedConsumer consumer)
      throws IOException;

  @FunctionalInterface
  interface StagedConsumer {
    ProcessResult consume(Path rootOwnedStage) throws IOException;
  }
}
