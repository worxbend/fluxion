package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class PrivilegedAtomicFilePublisher implements PrivilegedArtifactPublisher {

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private final ShellRunner shellRunner;
  private final DestinationValidator destinationValidator;

  PrivilegedAtomicFilePublisher(ShellRunner shellRunner) {
    this(shellRunner, TrustedDestinationAncestry::requireSafe);
  }

  PrivilegedAtomicFilePublisher(
      ShellRunner shellRunner, DestinationValidator destinationValidator) {
    this.shellRunner = Objects.requireNonNull(shellRunner);
    this.destinationValidator = Objects.requireNonNull(destinationValidator);
  }

  @Override
  public ProcessResult publish(Path source, Path destination, String mode, Sha256Digest expected)
      throws IOException {
    return consumeVerified(
        source,
        destination,
        mode,
        expected,
        staged -> {
          destinationValidator.requireSafe(destination);
          return run(
              List.of(
                  "sudo",
                  TrustedSystemExecutable.move().toString(),
                  "-f",
                  "-T",
                  "--",
                  staged.toString(),
                  destination.toString()));
        });
  }

  @Override
  public ProcessResult consumeVerified(
      Path source, Path stagingAnchor, String mode, Sha256Digest expected, StagedConsumer consumer)
      throws IOException {
    return consume(
        source,
        stagingAnchor,
        mode,
        staged -> {
          ProcessResult verification = verifyDigest(staged, expected, mode);
          if (!verification.isSuccess()) {
            return verification;
          }
          return consumer.consume(staged);
        });
  }

  @Override
  public ProcessResult consume(
      Path source, Path stagingAnchor, String mode, StagedConsumer consumer) throws IOException {
    destinationValidator.requireSafe(stagingAnchor);
    Path staged =
        stagingAnchor.resolveSibling(
            "." + stagingAnchor.getFileName() + ".sysboot-stage-" + UUID.randomUUID());
    boolean rootStaged = false;
    ProcessResult outcome = null;
    try {
      for (List<String> command : stageCommands(source, staged, stagingAnchor, mode)) {
        ProcessResult result = run(command);
        if (!result.isSuccess()) {
          outcome = result;
          break;
        }
        rootStaged = true;
      }
      if (outcome == null) {
        destinationValidator.requireSafe(staged);
        outcome = consumer.consume(staged);
      }
    } catch (IOException | RuntimeException failure) {
      cleanupAfterFailure(staged, rootStaged, failure);
      throw failure;
    }
    ProcessResult cleanupResult = cleanupStage(staged, rootStaged);
    if (!cleanupResult.isSuccess()) {
      return outcome.isSuccess() ? cleanupResult : withCleanupFailure(outcome, cleanupResult);
    }
    return outcome;
  }

  private ProcessResult withCleanupFailure(ProcessResult primary, ProcessResult cleanupResult) {
    String separator = primary.stderr().isBlank() ? "" : System.lineSeparator();
    return new ProcessResult(
        primary.exitCode(),
        primary.stdout(),
        primary.stderr()
            + separator
            + "Additionally failed to remove root-owned artifact stage: "
            + StepOutcome.detail(cleanupResult),
        primary.elapsed());
  }

  private void cleanupAfterFailure(Path staged, boolean rootStaged, Throwable failure) {
    if (!rootStaged) {
      return;
    }
    try {
      ProcessResult result = run(cleanup(staged));
      if (!result.isSuccess()) {
        failure.addSuppressed(
            new IOException(
                "Failed to remove root-owned artifact stage: " + StepOutcome.detail(result)));
      }
    } catch (RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private ProcessResult cleanupStage(Path staged, boolean rootStaged) {
    if (!rootStaged) {
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
    try {
      return run(cleanup(staged));
    } catch (RuntimeException cleanupFailure) {
      return failure("Failed to remove root-owned artifact stage");
    }
  }

  private List<List<String>> stageCommands(
      Path source, Path staged, Path stagingAnchor, String mode) throws IOException {
    return List.of(
        List.of(
            "sudo",
            TrustedSystemExecutable.install().toString(),
            "-d",
            "-m",
            "0755",
            PathRequirements.parent(stagingAnchor, "Privileged staging path").toString()),
        List.of(
            "sudo",
            TrustedSystemExecutable.install().toString(),
            "-m",
            mode,
            "--",
            source.toString(),
            staged.toString()));
  }

  private List<String> cleanup(Path staged) {
    return List.of(
        "sudo", TrustedSystemExecutable.remove().toString(), "-f", "--", staged.toString());
  }

  private ProcessResult verifyDigest(Path staged, Sha256Digest expected, String mode)
      throws IOException {
    if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)
        || Files.size(staged) > HttpBinaryDownloadClient.MAX_FILE_BYTES) {
      return failure("Root-owned artifact stage exceeds the maximum size");
    }
    int permissions = Integer.parseInt(mode, 8);
    if ((permissions & 0004) != 0 && (permissions & 0022) == 0) {
      return ArtifactDigests.sha256(staged, HttpBinaryDownloadClient.MAX_FILE_BYTES)
              .equals(expected)
          ? new ProcessResult(0, "", "", Duration.ZERO)
          : failure("Root-owned artifact stage failed SHA-256 verification");
    }
    ProcessResult result =
        run(
            List.of(
                "sudo", TrustedSystemExecutable.sha256sum().toString(), "--", staged.toString()));
    if (!result.isSuccess()) {
      return result;
    }
    String output = result.stdout().strip();
    int separator = output.indexOf(' ');
    String actual = separator < 0 ? output : output.substring(0, separator);
    return actual.equalsIgnoreCase(expected.value())
        ? result
        : failure("Root-owned artifact stage failed SHA-256 verification");
  }

  private ProcessResult run(List<String> command) {
    return shellRunner.run(command, Map.of(), TIMEOUT);
  }

  private ProcessResult failure(String message) {
    return new ProcessResult(1, "", message, Duration.ZERO);
  }

  @FunctionalInterface
  interface DestinationValidator {
    void requireSafe(Path destination) throws IOException;
  }
}
