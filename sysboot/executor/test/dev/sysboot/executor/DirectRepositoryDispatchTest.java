package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.StepResult;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectRepositoryDispatchTest {

  @TempDir Path tempDirectory;

  @Test
  void flatpakDirectStep_downloadsVerifiesAndRecordsThroughSharedSourceExecutor() throws Exception {
    Path descriptor = Files.writeString(tempDirectory.resolve("verified.flatpakrepo"), "verified");
    var observedUrl = new URI[1];
    var observedDigest = new Sha256Digest[1];
    SourceArtifactDownloadClient downloader =
        (url, digest) -> {
          observedUrl[0] = url;
          observedDigest[0] = digest;
          return descriptor;
        };
    var flatpakInstaller = mock(FlatpakRemoteInstaller.class);
    when(flatpakInstaller.addTrusted(module().asSourceSetup(), descriptor))
        .thenReturn(new StepResult.Success("flathub", Duration.ZERO));
    var sourceExecutor =
        new SourceSetupExecutor(
            mock(AptRepositoryInstaller.class),
            mock(RpmRepositoryInstaller.class),
            mock(PacmanRepositoryInstaller.class),
            mock(ZypperRepositoryInstaller.class),
            flatpakInstaller,
            downloader);
    var repository = new JsonStateRepository(tempDirectory.resolve("state"), new ObjectMapper());
    var skipEvaluator = SkipEvaluator.alwaysRun();
    var recorder =
        new RunStateRecorder(
            Optional.of(repository),
            "test-profile",
            skipEvaluator,
            new PhaseFingerprintCalculator());
    var itemExecution = new ItemExecution(skipEvaluator, recorder);
    var direct =
        new DirectModuleExecutor(
            sourceExecutor,
            mock(FileWriteExecutor.class),
            mock(FlatpakInstaller.class),
            mock(CompiledBinaryInstaller.class),
            skipEvaluator,
            recorder,
            itemExecution);

    boolean failed =
        direct.execute(module(), event -> {}, PhaseExecutors.forRunner(mockRunner()), mockRunner());

    assertThat(failed).isFalse();
    assertThat(observedUrl[0]).isEqualTo(module().url());
    assertThat(observedDigest[0]).isEqualTo(module().artifactSha256().orElseThrow());
    verify(flatpakInstaller).addTrusted(module().asSourceSetup(), descriptor);
    assertThat(repository.load("test-profile").orElseThrow().entries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.itemKey()).isEqualTo("flathub");
              assertThat(entry.itemType()).isEqualTo(dev.sysboot.core.ItemType.FLATPAK_REMOTE);
            });
  }

  private FlatpakRemoteModule module() {
    return new FlatpakRemoteModule(
        new ModuleName("flathub"),
        "flathub",
        URI.create("https://example.test/flathub.flatpakrepo"),
        true,
        Optional.of(new Sha256Digest("a".repeat(64))));
  }

  private dev.sysboot.core.ShellRunner mockRunner() {
    return (command, environment, timeout) ->
        new dev.sysboot.core.ProcessResult(0, "", "", Duration.ZERO);
  }
}
