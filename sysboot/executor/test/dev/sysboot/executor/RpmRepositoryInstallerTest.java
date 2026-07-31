package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.RpmRepositorySourceSetup;
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
class RpmRepositoryInstallerTest {

  @Mock private ShellRunner shellRunner;
  @TempDir Path tempDirectory;

  @Test
  void addTrusted_withVerifiedKey_neverPassesRemoteUrlAcrossPrivilegedBoundary() throws Exception {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(1)));
    Path verifiedKey = Files.writeString(tempDirectory.resolve("verified.key"), "key");
    var publisher = new RecordingArtifactPublisher(tempDirectory);

    StepResult result =
        new RpmRepositoryInstaller(shellRunner, publisher)
            .addTrusted(source(), Optional.of(verifiedKey));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    ArgumentCaptor<List<String>> commands = ArgumentCaptor.captor();
    verify(shellRunner).run(commands.capture(), any(), any());
    assertThat(commands.getValue()).containsExactly("sudo", "dnf", "makecache", "--refresh");
    assertThat(publisher.publications)
        .extracting(RecordingArtifactPublisher.Publication::destination)
        .containsExactly(
            Path.of("/etc/pki/rpm-gpg/sysboot-example.key"),
            Path.of("/etc/yum.repos.d/example.repo"));
    assertThat(commands.getValue().toString())
        .doesNotContain("/bin/bash", "curl", "https://example.test/key.gpg");
  }

  private RpmRepositorySourceSetup source() {
    return new RpmRepositorySourceSetup(
        new ModuleName("example"),
        "example",
        URI.create("https://example.test/fedora/$releasever/$basearch/stable"),
        Path.of("/etc/yum.repos.d/example.repo"),
        Optional.of(URI.create("https://example.test/key.gpg")),
        true,
        true,
        Optional.of(new Sha256Digest("a".repeat(64))));
  }
}
