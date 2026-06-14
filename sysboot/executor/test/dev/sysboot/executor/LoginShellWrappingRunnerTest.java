package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellKind;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LoginShellWrappingRunnerTest {

  @Mock private ShellRunner delegate;

  @Test
  void run_wrapsCommandInZshLoginShell() {
    when(delegate.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var runner = new LoginShellWrappingRunner(delegate, ShellKind.ZSH);
    runner.run(List.of("cargo", "install", "ripgrep"), Map.of(), Duration.ofMinutes(1));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate).run(captor.capture(), any(), any());

    List<String> wrapped = captor.getValue();
    assertThat(wrapped).startsWith("zsh", "--login", "-i", "-c");
    assertThat(wrapped.get(4)).contains("cargo").contains("install").contains("ripgrep");
  }

  @Test
  void run_wrapsCommandInBashLoginShell() {
    when(delegate.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var runner = new LoginShellWrappingRunner(delegate, ShellKind.BASH);
    runner.run(List.of("source", "~/.cargo/env"), Map.of(), Duration.ofSeconds(10));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate).run(captor.capture(), any(), any());
    assertThat(captor.getValue().get(0)).isEqualTo("bash");
  }

  @Test
  void run_argsWithSpacesAreQuoted() {
    when(delegate.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var runner = new LoginShellWrappingRunner(delegate, ShellKind.ZSH);
    runner.run(List.of("echo", "hello world"), Map.of(), Duration.ofSeconds(5));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate).run(captor.capture(), any(), any());
    assertThat(captor.getValue().get(4)).contains("'hello world'");
  }

  @Test
  void run_passesEnvThroughToDelegate() {
    when(delegate.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var runner = new LoginShellWrappingRunner(delegate, ShellKind.ZSH);
    Map<String, String> env = Map.of("FOO", "bar");
    runner.run(List.of("echo", "test"), env, Duration.ofSeconds(5));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    verify(delegate).run(any(), envCaptor.capture(), any());
    assertThat(envCaptor.getValue()).containsEntry("FOO", "bar");
  }
}
