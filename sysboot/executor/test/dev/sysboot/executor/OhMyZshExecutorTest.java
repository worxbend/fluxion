package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.ProcessResult;
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
        new ModuleName("oh-my-zsh"), Path.of("~/.oh-my-zsh"), Optional.empty());
  }

  @Test
  void execute_installerExitZero_returnsSuccess() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "Done", "", Duration.ofSeconds(10)));

    var executor = new OhMyZshExecutor(runner);
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void execute_installerExitOne_returnsFailure() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "Error", Duration.ofSeconds(5)));

    var executor = new OhMyZshExecutor(runner);
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
  }

  @Test
  @SuppressWarnings("unchecked")
  void execute_alwaysSetsRunzshAndChshEnv() {
    when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var executor = new OhMyZshExecutor(runner);
    executor.execute(module());

    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(runner).run(any(), envCaptor.capture(), any());

    Map<String, String> env = envCaptor.getValue();
    assertThat(env).containsEntry("RUNZSH", "no");
    assertThat(env).containsEntry("CHSH", "no");
  }
}
