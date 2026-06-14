package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellKind;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShellReloadExecutorTest {

  @Mock private ShellRunner runner;

  private ShellReloadModule module() {
    return new ShellReloadModule(new ModuleName("shell-reload"), ShellKind.ZSH);
  }

  @Test
  void execute_exitZero_returnsSuccess() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "Shell environment OK", "", Duration.ofSeconds(2)));

    var executor = new ShellReloadExecutor(runner);
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void execute_exitNonZero_returnsFailureWithHelpfulMessage() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "error in .zshrc", Duration.ofSeconds(2)));

    var executor = new ShellReloadExecutor(runner);
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).containsIgnoringCase(".zshrc");
  }

  @Test
  void execute_commandRunsZshLoginInteractive() {
    when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var executor = new ShellReloadExecutor(runner);
    executor.execute(module());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(runner).run(captor.capture(), any(), any());

    List<String> cmd = captor.getValue();
    assertThat(cmd).contains("zsh", "--login", "-i", "-c");
  }
}
