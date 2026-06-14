package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
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
class DefaultShellExecutorTest {

  @Mock private ShellRunner runner;

  @TempDir Path tempDir;

  @Test
  void execute_shellBinaryExists_runsChsh() throws IOException {
    Path zsh = tempDir.resolve("zsh");
    Files.createFile(zsh);
    zsh.toFile().setExecutable(true);

    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofSeconds(1)));

    var executor = new DefaultShellExecutor(runner);
    var module = new DefaultShellModule(new ModuleName("default-shell"), zsh, Optional.empty());
    StepResult result = executor.execute(module);

    assertThat(result).isInstanceOf(StepResult.Success.class);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(runner).run(captor.capture(), any(), any());
    assertThat(captor.getValue()).contains("chsh", "-s", zsh.toString());
  }

  @Test
  void execute_shellBinaryMissing_failsWithoutRunningChsh() {
    var executor = new DefaultShellExecutor(runner);
    var module =
        new DefaultShellModule(
            new ModuleName("default-shell"), Path.of("/nonexistent/bin/zsh"), Optional.empty());

    StepResult result = executor.execute(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("not found")
        .contains("/nonexistent/bin/zsh");
    verify(runner, never()).run(any(), any(), any());
  }

  @Test
  void execute_chshFails_returnsFailure() throws IOException {
    Path zsh = tempDir.resolve("zsh");
    Files.createFile(zsh);
    zsh.toFile().setExecutable(true);

    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "chsh: user not found", Duration.ofSeconds(1)));

    var executor = new DefaultShellExecutor(runner);
    var module = new DefaultShellModule(new ModuleName("default-shell"), zsh, Optional.empty());

    StepResult result = executor.execute(module);
    assertThat(result).isInstanceOf(StepResult.Failure.class);
  }
}
