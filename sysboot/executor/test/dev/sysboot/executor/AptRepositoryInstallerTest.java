package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AptRepositoryInstallerTest {

  @Mock private ShellRunner shellRunner;
  @TempDir Path tempDirectory;

  @Test
  void addTrusted_withVerifiedKey_publishesOnlyDigestBoundRootStages() throws Exception {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(1)));
    Path verifiedKey = Files.writeString(tempDirectory.resolve("verified.key"), "key");
    var publisher = new RecordingArtifactPublisher(tempDirectory);

    StepResult result =
        new AptRepositoryInstaller(shellRunner, publisher)
            .addTrusted(source(), Optional.of(verifiedKey));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    ArgumentCaptor<List<String>> commands = ArgumentCaptor.captor();
    verify(shellRunner).run(commands.capture(), any(), any());
    assertThat(commands.getValue()).containsExactly("sudo", "apt-get", "update");
    assertThat(publisher.consumedSources).hasSize(1).allMatch(path -> !Files.exists(path));
    assertThat(publisher.publications)
        .extracting(RecordingArtifactPublisher.Publication::destination)
        .containsExactly(
            Path.of("/etc/apt/keyrings/example.gpg"),
            Path.of("/etc/apt/sources.list.d/example.list"));
  }

  @Test
  void addTrusted_whenRefreshFails_reportsFailureAfterVerifiedPublications() throws Exception {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(2, "", "refresh failed", Duration.ZERO));
    Path verifiedKey = Files.writeString(tempDirectory.resolve("verified.key"), "key");
    var publisher = new RecordingArtifactPublisher(tempDirectory);

    StepResult result =
        new AptRepositoryInstaller(shellRunner, publisher)
            .addTrusted(source(), Optional.of(verifiedKey));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(publisher.publications).hasSize(2);
  }

  private AptRepositorySourceSetup source() {
    return new AptRepositorySourceSetup(
        new ModuleName("example"),
        "deb [signed-by=/etc/apt/keyrings/example.gpg] https://example.test/debian stable main",
        Path.of("/etc/apt/sources.list.d/example.list"),
        Optional.of(URI.create("https://example.test/key")),
        Optional.of(Path.of("/etc/apt/keyrings/example.gpg")),
        Optional.of(new Sha256Digest("a".repeat(64))));
  }
}
