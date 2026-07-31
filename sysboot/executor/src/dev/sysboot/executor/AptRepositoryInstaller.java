package dev.sysboot.executor;

import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class AptRepositoryInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;
  private final PrivilegedArtifactPublisher publisher;

  public AptRepositoryInstaller(ShellRunner shellRunner) {
    this(shellRunner, new PrivilegedAtomicFilePublisher(shellRunner));
  }

  AptRepositoryInstaller(ShellRunner shellRunner, PrivilegedArtifactPublisher publisher) {
    this.shellRunner = shellRunner;
    this.publisher = publisher;
  }

  StepResult addTrusted(AptRepositorySourceSetup setup, Optional<Path> verifiedKey) {
    Path sourceFile = null;
    try {
      sourceFile = Files.createTempFile("sysboot-apt-", ".list");
      byte[] sourceContent =
          (setup.sourceEntry() + System.lineSeparator())
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Files.write(sourceFile, sourceContent);
      ProcessResult result = publish(setup, verifiedKey, sourceFile, sourceContent);
      return result(setup.sourceListPath().toString(), result);
    } catch (IOException e) {
      return new StepResult.Failure(
          setup.sourceListPath().toString(),
          "Cannot prepare trusted APT source configuration",
          1,
          Duration.ZERO);
    } finally {
      delete(sourceFile);
    }
  }

  private ProcessResult publish(
      AptRepositorySourceSetup setup,
      Optional<Path> verifiedKey,
      Path sourceFile,
      byte[] sourceContent)
      throws IOException {
    ProcessResult result = new ProcessResult(0, "", "", Duration.ZERO);
    if (verifiedKey.isPresent()) {
      result =
          VerifiedRepositoryKeyPublisher.publish(
              verifiedKey.orElseThrow(),
              setup.artifactSha256().orElseThrow(),
              setup.keyringPath().orElseThrow(),
              publisher);
    }
    if (result.isSuccess()) {
      result =
          publisher.publish(
              sourceFile, setup.sourceListPath(), "0644", ArtifactDigests.sha256(sourceContent));
    }
    if (result.isSuccess()) {
      result = run(List.of("sudo", "apt-get", "update"));
    }
    return result;
  }

  private ProcessResult run(List<String> command) {
    return shellRunner.run(command, Map.of(), INSTALL_TIMEOUT);
  }

  private StepResult result(String item, ProcessResult result) {
    if (result.isSuccess()) {
      return new StepResult.Success(item, result.elapsed());
    }
    return new StepResult.Failure(
        item, result.stdout() + result.stderr(), result.exitCode(), result.elapsed());
  }

  private void delete(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // The command result remains authoritative.
    }
  }
}
