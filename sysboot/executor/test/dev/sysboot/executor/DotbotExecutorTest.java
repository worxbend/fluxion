package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
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
class DotbotExecutorTest {

  @Mock private ShellRunner runner;

  @TempDir private Path tempDir;

  private DotbotModule module() {
    return new DotbotModule(
        new ModuleName("dotfiles"),
        Path.of("~/.dotfiles/install.conf.yaml"),
        "v0.2.1",
        "dotbot",
        Optional.empty());
  }

  private Path installer() {
    return tempDir.resolve("dotbot");
  }

  @Test
  void execute_exitZero_returnsSuccess() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "Dotbot done", "", Duration.ofSeconds(2)));

    var executor = new DotbotExecutor(runner, ignored -> installer());
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void execute_exitOne_returnsFailure() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "Error in dotbot", Duration.ofSeconds(1)));

    var executor = new DotbotExecutor(runner, ignored -> installer());
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("exited");
  }

  @Test
  void execute_passesConfigToCommand() {
    when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    Path installer = installer();
    var executor = new DotbotExecutor(runner, ignored -> installer);
    executor.execute(module());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(runner).run(captor.capture(), any(), any());

    List<String> cmd = captor.getValue();
    assertThat(cmd.getFirst()).isEqualTo(installer.toString());
    assertThat(cmd).contains("--config");
    assertThat(String.join(" ", cmd)).contains("install.conf.yaml");
  }
}
