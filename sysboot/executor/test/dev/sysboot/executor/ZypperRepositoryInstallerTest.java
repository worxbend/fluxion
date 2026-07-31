package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ZypperRepositoryInstallerTest {

  @Mock private ShellRunner shellRunner;
  @TempDir Path tempDirectory;

  @Test
  void addTrusted_withVerifiedKey_usesOnlyLocalParsedKeyInSudoCommands() throws Exception {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(1)));
    Path verifiedKey = Files.writeString(tempDirectory.resolve("verified.key"), "key");
    var publisher = new RecordingArtifactPublisher(tempDirectory);

    StepResult result =
        new ZypperRepositoryInstaller(shellRunner, publisher)
            .addTrusted(source(), Optional.of(verifiedKey));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    ArgumentCaptor<List<String>> commands = ArgumentCaptor.captor();
    verify(shellRunner).run(commands.capture(), any(), any());
    assertThat(commands.getValue()).containsExactly("sudo", "zypper", "refresh");
    assertThat(publisher.publications)
        .extracting(RecordingArtifactPublisher.Publication::destination)
        .containsExactly(
            Path.of("/etc/zypp/keys/sysboot-example.key"),
            Path.of("/etc/zypp/repos.d/example.repo"));
    assertThat(commands.getValue().toString())
        .doesNotContain("/bin/bash", "https://example.test/key");
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void addTrusted_writesConfiguredAutoRefresh(boolean autoRefresh) {
    var installedContent = new AtomicReference<String>();
    var publisher = new RecordingArtifactPublisher(tempDirectory);
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(1)));

    StepResult result =
        new ZypperRepositoryInstaller(shellRunner, publisher)
            .addTrusted(source(autoRefresh), Optional.empty());
    publisher.publications.stream()
        .filter(publication -> publication.destination().toString().endsWith(".repo"))
        .findFirst()
        .ifPresent(
            publication ->
                installedContent.set(
                    new String(publication.content(), java.nio.charset.StandardCharsets.UTF_8)));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(installedContent.get())
        .contains("autorefresh=" + (autoRefresh ? "1" : "0"))
        .doesNotContain("autorefresh=" + (autoRefresh ? "0" : "1"));
  }

  private ZypperRepositorySourceSetup source() {
    return source(true);
  }

  private ZypperRepositorySourceSetup source(boolean autoRefresh) {
    return new ZypperRepositorySourceSetup(
        new ModuleName("example"),
        "example",
        URI.create("https://example.test/repository"),
        Path.of("/etc/zypp/repos.d/example.repo"),
        Optional.of(URI.create("https://example.test/key")),
        true,
        true,
        autoRefresh,
        Optional.of(new Sha256Digest("a".repeat(64))));
  }
}
