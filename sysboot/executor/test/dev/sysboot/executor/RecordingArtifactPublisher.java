package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

final class RecordingArtifactPublisher implements PrivilegedArtifactPublisher {

  final List<Publication> publications = new ArrayList<>();
  final List<Path> consumedSources = new ArrayList<>();
  private final Path tempDirectory;

  RecordingArtifactPublisher(Path tempDirectory) {
    this.tempDirectory = tempDirectory;
  }

  @Override
  public ProcessResult publish(Path source, Path destination, String mode, Sha256Digest expected)
      throws IOException {
    publications.add(new Publication(destination, expected, Files.readAllBytes(source)));
    return success();
  }

  @Override
  public ProcessResult consumeVerified(
      Path source, Path stagingAnchor, String mode, Sha256Digest expected, StagedConsumer consumer)
      throws IOException {
    return consume(source, stagingAnchor, mode, consumer);
  }

  @Override
  public ProcessResult consume(
      Path source, Path stagingAnchor, String mode, StagedConsumer consumer) throws IOException {
    Path staged = Files.createTempFile(tempDirectory, "root-owned-stage-", ".artifact");
    try {
      Files.copy(source, staged, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      consumedSources.add(staged);
      return consumer.consume(staged);
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  private ProcessResult success() {
    return new ProcessResult(0, "", "", Duration.ZERO);
  }

  record Publication(Path destination, Sha256Digest digest, byte[] content) {}
}
