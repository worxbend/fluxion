package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceSetupTrustTest {

  @TempDir Path tempDir;

  @Test
  void execute_whenVerificationFails_performsNoPrivilegedMutation() {
    ShellRunner runner = org.mockito.Mockito.mock(ShellRunner.class);
    SourceSetupExecutor executor =
        executor(
            runner,
            (url, digest) -> {
              throw new IOException("mismatch");
            });

    StepResult result = executor.execute(source(Optional.of(digest())));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    verify(runner, never()).run(any(), any(), any());
  }

  @Test
  void execute_afterVerification_neverPassesRemoteOrMutableArtifactToRefresh() throws Exception {
    ShellRunner runner = org.mockito.Mockito.mock(ShellRunner.class);
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(1)));
    Path verifiedKey = tempDir.resolve("verified.key");
    Files.writeString(verifiedKey, "trusted");
    SourceSetupExecutor executor = executor(runner, (url, digest) -> verifiedKey);

    StepResult result = executor.execute(source(Optional.of(digest())));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    var commands = org.mockito.ArgumentCaptor.<java.util.List<String>>captor();
    verify(runner, atLeastOnce()).run(commands.capture(), any(), any());
    assertThat(commands.getAllValues())
        .singleElement()
        .satisfies(
            command ->
                assertThat(command)
                    .containsExactly("sudo", "dnf", "makecache", "--refresh")
                    .doesNotContain(verifiedKey.toString()));
    assertThat(commands.getAllValues().toString()).doesNotContain("https://example.test/key");
  }

  @Test
  void commandPreview_includesTrustBindingWithoutRemoteUrl() {
    SourceSetupExecutor executor =
        executor(
            org.mockito.Mockito.mock(ShellRunner.class),
            (url, digest) -> tempDir.resolve("unused"));

    assertThat(executor.commandPreview(source(Optional.of(digest()))))
        .contains("verify-sha256=" + "a".repeat(64))
        .noneMatch(argument -> argument.contains("https://"));
  }

  private SourceSetupExecutor executor(
      ShellRunner runner, SourceArtifactDownloadClient downloader) {
    var publisher = new RecordingArtifactPublisher(tempDir);
    return new SourceSetupExecutor(
        new AptRepositoryInstaller(runner, publisher),
        new RpmRepositoryInstaller(runner, publisher),
        new PacmanRepositoryInstaller(runner),
        new ZypperRepositoryInstaller(runner, publisher),
        new FlatpakRemoteInstaller(runner, publisher),
        downloader);
  }

  private RpmRepositorySourceSetup source(Optional<Sha256Digest> checksum) {
    return new RpmRepositorySourceSetup(
        new ModuleName("example"),
        "example",
        URI.create("https://example.test/repo"),
        Path.of("/etc/yum.repos.d/example.repo"),
        Optional.of(URI.create("https://example.test/key")),
        true,
        true,
        checksum);
  }

  private Sha256Digest digest() {
    return new Sha256Digest("a".repeat(64));
  }
}
