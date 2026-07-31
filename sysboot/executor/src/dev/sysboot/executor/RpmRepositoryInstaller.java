package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RpmRepositoryInstaller {

  private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

  private final ShellRunner shellRunner;
  private final PrivilegedArtifactPublisher publisher;

  public RpmRepositoryInstaller(ShellRunner shellRunner) {
    this(shellRunner, new PrivilegedAtomicFilePublisher(shellRunner));
  }

  RpmRepositoryInstaller(ShellRunner shellRunner, PrivilegedArtifactPublisher publisher) {
    this.shellRunner = shellRunner;
    this.publisher = publisher;
  }

  StepResult addTrusted(RpmRepositorySourceSetup setup, Optional<Path> verifiedKey) {
    Path repoFile = null;
    try {
      repoFile = Files.createTempFile("sysboot-rpm-", ".repo");
      Path installedKey = installedKey(setup.repositoryId());
      byte[] repoContent =
          trustedRepoFileContent(setup, verifiedKey, installedKey)
              .getBytes(java.nio.charset.StandardCharsets.UTF_8);
      Files.write(repoFile, repoContent);
      ProcessResult result = publish(setup, verifiedKey, installedKey, repoFile, repoContent);
      return result(setup.repoFilePath().toString(), result);
    } catch (IOException e) {
      return new StepResult.Failure(
          setup.repoFilePath().toString(),
          "Cannot prepare trusted RPM source configuration",
          1,
          Duration.ZERO);
    } finally {
      delete(repoFile);
    }
  }

  private ProcessResult publish(
      RpmRepositorySourceSetup setup,
      Optional<Path> verifiedKey,
      Path installedKey,
      Path repoFile,
      byte[] repoContent)
      throws IOException {
    ProcessResult result = new ProcessResult(0, "", "", Duration.ZERO);
    if (verifiedKey.isPresent()) {
      result =
          VerifiedRepositoryKeyPublisher.publish(
              verifiedKey.orElseThrow(),
              setup.artifactSha256().orElseThrow(),
              installedKey,
              publisher);
    }
    if (result.isSuccess()) {
      result =
          publisher.publish(
              repoFile, setup.repoFilePath(), "0644", ArtifactDigests.sha256(repoContent));
    }
    if (result.isSuccess()) {
      result = run(List.of("sudo", "dnf", "makecache", "--refresh"));
    }
    return result;
  }

  private ProcessResult run(List<String> command) {
    return shellRunner.run(command, Map.of(), INSTALL_TIMEOUT);
  }

  private String trustedRepoFileContent(
      RpmRepositorySourceSetup setup, Optional<Path> verifiedKey, Path installedKey) {
    var builder = new StringBuilder();
    builder.append('[').append(setup.repositoryId()).append("]\n");
    builder.append("name=").append(setup.repositoryId()).append('\n');
    builder.append("baseurl=").append(setup.baseUrl()).append('\n');
    builder.append("enabled=").append(setup.enabled() ? "1" : "0").append('\n');
    builder.append("gpgcheck=").append(setup.gpgCheck() ? "1" : "0").append('\n');
    if (verifiedKey.isPresent()) {
      builder.append("gpgkey=").append(installedKey.toUri()).append('\n');
    }
    return builder.toString();
  }

  private Path installedKey(String repositoryId) {
    String safeName = repositoryId.replaceAll("[^A-Za-z0-9._-]", "_");
    return Path.of("/etc/pki/rpm-gpg/sysboot-" + safeName + ".key");
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
