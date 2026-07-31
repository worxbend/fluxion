package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.ToolchainKind;
import dev.sysboot.core.ToolchainModule;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolchainExecutorTest {

  @Mock private ShellRunner runner;

  private ToolchainModule rustupModule() {
    return new ToolchainModule(
        new ModuleName("rustup"),
        ToolchainKind.RUSTUP,
        "https://sh.rustup.rs",
        digest(),
        List.of("-y"),
        Optional.empty(),
        Optional.empty(),
        false);
  }

  @Test
  void execute_exitZero_returnsSuccess() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "installed", "", Duration.ofSeconds(30)));

    var executor = new ToolchainExecutor(runner, new FakeScriptDownloader());
    StepResult result = executor.execute(rustupModule());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void execute_exitNonZero_returnsFailure() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "install failed", Duration.ofSeconds(5)));

    var executor = new ToolchainExecutor(runner, new FakeScriptDownloader());
    StepResult result = executor.execute(rustupModule());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void execute_rustup_setsCargoAndRustupEnvVars() {
    when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var executor = new ToolchainExecutor(runner, new FakeScriptDownloader());
    executor.execute(rustupModule());

    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(runner).run(any(), envCaptor.capture(), any());

    Map<String, String> env = envCaptor.getValue();
    assertThat(env).containsKey("CARGO_HOME");
    assertThat(env).containsKey("RUSTUP_HOME");
  }

  @Test
  void execute_sdkman_setsSdkmanDir() {
    when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var sdkmanModule =
        new ToolchainModule(
            new ModuleName("sdkman"),
            ToolchainKind.SDKMAN,
            "https://get.sdkman.io",
            digest(),
            List.of(),
            Optional.empty(),
            Optional.empty(),
            false);

    var executor = new ToolchainExecutor(runner, new FakeScriptDownloader());
    executor.execute(sdkmanModule);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(runner).run(any(), envCaptor.capture(), any());
    assertThat(envCaptor.getValue()).containsKey("SDKMAN_DIR");
  }

  @Test
  void execute_whenVerificationFails_doesNotRunInstaller() {
    ScriptDownloadClient failing =
        (url, sha256) -> {
          throw new java.io.IOException("SHA-256 mismatch");
        };

    StepResult result = new ToolchainExecutor(runner, failing).execute(rustupModule());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    verifyNoInteractions(runner);
  }

  private static Sha256Digest digest() {
    return new Sha256Digest("0000000000000000000000000000000000000000000000000000000000000000");
  }

  private static final class FakeScriptDownloader implements ScriptDownloadClient {
    @Override
    public java.nio.file.Path download(java.net.URI url, Sha256Digest sha256)
        throws java.io.IOException {
      var script = java.nio.file.Files.createTempFile("toolchain-test-", ".sh");
      java.nio.file.Files.writeString(script, "#!/bin/sh\n");
      return script;
    }
  }
}
