package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OhMyZshExecutorTest {

  @Mock private ShellRunner runner;

  private OhMyZshModule module() {
    return new OhMyZshModule(
        new ModuleName("oh-my-zsh"),
        Path.of("~/.oh-my-zsh"),
        "c5ba74cf02cce4c342153f79089100194f30940f",
        new Sha256Digest("95118b50d062198597e2b73d3a57b609fd95ca68cdc86faf4460d955f0172b61"),
        Optional.empty());
  }

  @Test
  void execute_installerExitZero_returnsSuccess() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "Done", "", Duration.ofSeconds(10)));

    var executor = new OhMyZshExecutor(runner, new FakeScriptDownloader());
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void execute_installerExitOne_returnsFailure() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "Error", Duration.ofSeconds(5)));

    var executor = new OhMyZshExecutor(runner, new FakeScriptDownloader());
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void execute_alwaysSetsRunzshAndChshEnv() {
    when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var executor = new OhMyZshExecutor(runner, new FakeScriptDownloader());
    executor.execute(module());

    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(runner).run(any(), envCaptor.capture(), any());

    Map<String, String> env = envCaptor.getValue();
    assertThat(env).containsEntry("RUNZSH", "no");
    assertThat(env).containsEntry("CHSH", "no");
    assertThat(env).containsEntry("ZSH", module().installDir().toString());
  }

  @Test
  void execute_whenVerificationFails_doesNotRunInstaller() {
    ScriptDownloadClient failing =
        (url, sha256) -> {
          throw new java.io.IOException("SHA-256 mismatch");
        };

    StepResult result = new OhMyZshExecutor(runner, failing).execute(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    verifyNoInteractions(runner);
  }

  @Test
  void execute_downloadsInstallerFromImmutableRevision() {
    when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));
    var downloader = new RecordingDownloader();

    new OhMyZshExecutor(runner, downloader).execute(module());

    assertThat(downloader.url.toString())
        .contains("/c5ba74cf02cce4c342153f79089100194f30940f/tools/install.sh");
    assertThat(downloader.url.toString()).doesNotContain("/master/");
  }

  private static final class FakeScriptDownloader implements ScriptDownloadClient {
    @Override
    public Path download(java.net.URI url, Sha256Digest sha256) throws java.io.IOException {
      var script = java.nio.file.Files.createTempFile("oh-my-zsh-test-", ".sh");
      java.nio.file.Files.writeString(script, "#!/bin/sh\n");
      return script;
    }
  }

  private static final class RecordingDownloader implements ScriptDownloadClient {
    private java.net.URI url;

    @Override
    public Path download(java.net.URI url, Sha256Digest sha256) throws java.io.IOException {
      this.url = url;
      var script = java.nio.file.Files.createTempFile("oh-my-zsh-test-", ".sh");
      java.nio.file.Files.writeString(script, "#!/bin/sh\n");
      return script;
    }
  }
}
