package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivilegedAtomicFilePublisherTest {

  @TempDir Path tempDirectory;

  @Test
  void stagedDigestFailureCleansRootStageWithoutTouchingOriginalDestination() throws Exception {
    Path source = Files.writeString(tempDirectory.resolve("source"), "replacement");
    Path destination = Files.writeString(tempDirectory.resolve("destination"), "original");
    var runner = new RootFileRunner(Pause.NONE);
    var publisher = new PrivilegedAtomicFilePublisher(runner, ignored -> {});

    ProcessResult result =
        publisher.publish(
            source, destination, "0644", ArtifactDigests.sha256("different".getBytes()));

    assertThat(result.isSuccess()).isFalse();
    assertThat(Files.readString(destination)).isEqualTo("original");
    assertThat(runner.commands)
        .anySatisfy(
            command ->
                assertThat(command).startsWith("sudo", TrustedSystemExecutable.remove().toString()))
        .noneSatisfy(
            command ->
                assertThat(command).startsWith("sudo", TrustedSystemExecutable.move().toString()));
    assertNoRootStage();
  }

  @Test
  void swapBeforeRootCopyFailsClosedAgainstPreviouslyVerifiedDigest() throws Exception {
    Path source = Files.writeString(tempDirectory.resolve("source-before"), "trusted");
    Path destination = Files.writeString(tempDirectory.resolve("destination-before"), "original");
    var runner = new RootFileRunner(Pause.BEFORE_COPY);
    var publisher = new PrivilegedAtomicFilePublisher(runner, ignored -> {});

    ProcessResult result;
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future =
          executor.submit(
              () ->
                  publisher.publish(
                      source, destination, "0644", ArtifactDigests.sha256("trusted".getBytes())));
      assertThat(runner.copyBoundary.await(5, TimeUnit.SECONDS)).isTrue();
      Files.writeString(source, "attacker");
      runner.release.countDown();
      result = future.get(5, TimeUnit.SECONDS);
    }

    assertThat(result.isSuccess()).isFalse();
    assertThat(Files.readString(destination)).isEqualTo("original");
    assertNoRootStage();
  }

  @Test
  void swapAfterRootCopyCannotChangeTheVerifiedStagedInode() throws Exception {
    Path source = Files.writeString(tempDirectory.resolve("source-after"), "trusted");
    Path destination = Files.writeString(tempDirectory.resolve("destination-after"), "original");
    var runner = new RootFileRunner(Pause.AFTER_COPY);
    var publisher = new PrivilegedAtomicFilePublisher(runner, ignored -> {});

    ProcessResult result;
    try (var executor = Executors.newSingleThreadExecutor()) {
      var future =
          executor.submit(
              () ->
                  publisher.publish(
                      source, destination, "0644", ArtifactDigests.sha256("trusted".getBytes())));
      assertThat(runner.copyBoundary.await(5, TimeUnit.SECONDS)).isTrue();
      Files.writeString(source, "attacker");
      runner.release.countDown();
      result = future.get(5, TimeUnit.SECONDS);
    }

    assertThat(result.isSuccess()).isTrue();
    assertThat(Files.readString(destination)).isEqualTo("trusted");
    assertNoRootStage();
  }

  @Test
  void consume_whenRootStageCleanupFails_reportsFailure() throws Exception {
    Path source = Files.writeString(tempDirectory.resolve("source-cleanup"), "trusted");
    Path destination = tempDirectory.resolve("destination-cleanup");
    var runner = new CleanupFailingRunner();
    var publisher = new PrivilegedAtomicFilePublisher(runner, ignored -> {});

    ProcessResult result =
        publisher.consume(
            source, destination, "0444", ignored -> new ProcessResult(0, "", "", Duration.ZERO));

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.exitCode()).isEqualTo(23);
    assertThat(result.stderr()).contains("distinct cleanup detail");
    assertThat(result.elapsed()).isEqualTo(Duration.ofSeconds(3));
  }

  @Test
  void consume_whenConsumerAndCleanupFail_preservesBothFailures() throws Exception {
    Path source = Files.writeString(tempDirectory.resolve("source-double-failure"), "trusted");
    Path destination = tempDirectory.resolve("destination-double-failure");
    var publisher = new PrivilegedAtomicFilePublisher(new CleanupFailingRunner(), ignored -> {});

    ProcessResult result =
        publisher.consume(
            source,
            destination,
            "0444",
            ignored -> new ProcessResult(7, "", "consumer failed", Duration.ZERO));

    assertThat(result.exitCode()).isEqualTo(7);
    assertThat(result.stderr())
        .contains("consumer failed")
        .contains("Additionally failed to remove root-owned artifact stage")
        .contains("distinct cleanup detail");
  }

  @Test
  void consume_whenConsumerThrowsAndCleanupFails_suppressesActualCleanupDetail() throws Exception {
    Path source = Files.writeString(tempDirectory.resolve("source-thrown-failure"), "trusted");
    Path destination = tempDirectory.resolve("destination-thrown-failure");
    var publisher = new PrivilegedAtomicFilePublisher(new CleanupFailingRunner(), ignored -> {});

    assertThatThrownBy(
            () ->
                publisher.consume(
                    source,
                    destination,
                    "0444",
                    ignored -> {
                      throw new IOException("consumer exception");
                    }))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("consumer exception")
        .satisfies(
            failure ->
                assertThat(failure.getSuppressed())
                    .anySatisfy(
                        suppressed ->
                            assertThat(suppressed.getMessage())
                                .contains("distinct cleanup detail")));
  }

  @Test
  void publish_whenReadableArtifactExceedsLegacyDigestLimit_usesDownloadLimit() throws Exception {
    byte[] content = new byte[32 * 1024 * 1024 + 1];
    content[content.length - 1] = 1;
    Path source = Files.write(tempDirectory.resolve("large-source"), content);
    Path destination = tempDirectory.resolve("large-destination");
    var publisher =
        new PrivilegedAtomicFilePublisher(new RootFileRunner(Pause.NONE), ignored -> {});

    ProcessResult result =
        publisher.publish(
            source,
            destination,
            "0444",
            ArtifactDigests.sha256(source, HttpBinaryDownloadClient.MAX_FILE_BYTES));

    assertThat(result.isSuccess()).isTrue();
    assertThat(Files.size(destination)).isEqualTo(content.length);
  }

  @Test
  void consumeVerified_whenRootStageExceedsDownloadLimit_rejectsBeforeHashing() throws Exception {
    Path source = Files.writeString(tempDirectory.resolve("small-source"), "trusted");
    Path destination = tempDirectory.resolve("oversized-destination");
    var runner = new OversizedStageRunner();
    var publisher = new PrivilegedAtomicFilePublisher(runner, ignored -> {});
    var consumed = new java.util.concurrent.atomic.AtomicBoolean();

    ProcessResult result =
        publisher.consumeVerified(
            source,
            destination,
            "0750",
            ArtifactDigests.sha256(source),
            ignored -> {
              consumed.set(true);
              return new ProcessResult(0, "", "", Duration.ZERO);
            });

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.stderr()).contains("maximum size");
    assertThat(runner.hashAttempted).isFalse();
    assertThat(consumed).isFalse();
  }

  private void assertNoRootStage() throws IOException {
    try (var entries = Files.list(tempDirectory)) {
      assertThat(entries.map(path -> path.getFileName().toString()).toList())
          .noneMatch(name -> name.contains(".sysboot-stage-"));
    }
  }

  private enum Pause {
    NONE,
    BEFORE_COPY,
    AFTER_COPY
  }

  private static final class RootFileRunner implements ShellRunner {

    private final Pause pause;
    private final CountDownLatch copyBoundary = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final List<List<String>> commands = new ArrayList<>();

    private RootFileRunner(Pause pause) {
      this.pause = pause;
    }

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      commands.add(List.copyOf(command));
      try {
        if (command.get(1).equals(TrustedSystemExecutable.sha256sum().toString())) {
          String digest = ArtifactDigests.sha256(Path.of(command.getLast())).value();
          return new ProcessResult(
              0, digest + "  " + command.getLast() + System.lineSeparator(), "", Duration.ZERO);
        }
        apply(command);
        return new ProcessResult(0, "", "", Duration.ZERO);
      } catch (IOException | InterruptedException e) {
        Thread.currentThread().interrupt();
        return new ProcessResult(1, "", e.getMessage(), Duration.ZERO);
      }
    }

    private void apply(List<String> command) throws IOException, InterruptedException {
      String executable = command.get(1);
      if (executable.equals(TrustedSystemExecutable.install().toString())
          && command.contains("-d")) {
        Files.createDirectories(Path.of(command.getLast()));
        return;
      }
      if (executable.equals(TrustedSystemExecutable.install().toString())) {
        pause(Pause.BEFORE_COPY);
        Files.copy(
            Path.of(command.get(command.size() - 2)),
            Path.of(command.getLast()),
            StandardCopyOption.REPLACE_EXISTING);
        pause(Pause.AFTER_COPY);
        return;
      }
      if (executable.equals(TrustedSystemExecutable.move().toString())) {
        Files.move(
            Path.of(command.get(command.size() - 2)),
            Path.of(command.getLast()),
            StandardCopyOption.REPLACE_EXISTING);
        return;
      }
      if (executable.equals(TrustedSystemExecutable.remove().toString())) {
        Files.deleteIfExists(Path.of(command.getLast()));
      }
    }

    private void pause(Pause boundary) throws InterruptedException {
      if (pause != boundary) {
        return;
      }
      copyBoundary.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) {
        throw new InterruptedException("test did not release root copy");
      }
    }
  }

  private static final class CleanupFailingRunner implements ShellRunner {

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      if (command.get(1).equals(TrustedSystemExecutable.remove().toString())) {
        return new ProcessResult(
            23, "cleanup stdout", "distinct cleanup detail", Duration.ofSeconds(3));
      }
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
  }

  private static final class OversizedStageRunner implements ShellRunner {

    private boolean hashAttempted;

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      try {
        String executable = command.get(1);
        if (executable.equals(TrustedSystemExecutable.sha256sum().toString())) {
          hashAttempted = true;
          return new ProcessResult(0, "", "", Duration.ZERO);
        }
        if (executable.equals(TrustedSystemExecutable.install().toString())
            && command.contains("-d")) {
          Files.createDirectories(Path.of(command.getLast()));
        } else if (executable.equals(TrustedSystemExecutable.install().toString())) {
          Path staged = Path.of(command.getLast());
          try (var channel =
              java.nio.channels.FileChannel.open(
                  staged,
                  java.nio.file.StandardOpenOption.CREATE,
                  java.nio.file.StandardOpenOption.WRITE)) {
            channel.position(HttpBinaryDownloadClient.MAX_FILE_BYTES);
            channel.write(java.nio.ByteBuffer.wrap(new byte[] {1}));
          }
        } else if (executable.equals(TrustedSystemExecutable.remove().toString())) {
          Files.deleteIfExists(Path.of(command.getLast()));
        }
        return new ProcessResult(0, "", "", Duration.ZERO);
      } catch (IOException e) {
        return new ProcessResult(1, "", e.getMessage(), Duration.ZERO);
      }
    }
  }
}
