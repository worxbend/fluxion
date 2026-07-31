package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellKind;
import dev.sysboot.core.ShellRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
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
    assertThat(captor.getValue())
        .containsExactly("bash", "--login", "-i", "-c", "'source' '~/.cargo/env'");
  }

  @Test
  void run_wrapsCommandInPortableShLoginShell() {
    when(delegate.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var runner = new LoginShellWrappingRunner(delegate, ShellKind.SH);
    runner.run(List.of("printf", "%s", "ready"), Map.of(), Duration.ofSeconds(10));

    verify(delegate)
        .run(
            eq(List.of("sh", "-l", "-i", "-c", "'printf' '%s' 'ready'")),
            eq(Map.of()),
            eq(Duration.ofSeconds(10)));
  }

  @Test
  void run_everyArgumentIsPosixQuoted() {
    when(delegate.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var runner = new LoginShellWrappingRunner(delegate, ShellKind.ZSH);
    runner.run(
        List.of(
            "echo",
            "",
            "hello world",
            "\ttabbed\t",
            "line one\nline two",
            "it's",
            "*",
            "$(touch /tmp/x)"),
        Map.of(),
        Duration.ofSeconds(5));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(delegate).run(captor.capture(), any(), any());
    assertThat(captor.getValue().get(4))
        .isEqualTo(
            "'echo' '' 'hello world' '\ttabbed\t' 'line one\nline two' 'it'\\''s' '*'"
                + " '$(touch /tmp/x)'");
  }

  @Test
  void run_adversarialArguments_hermeticShellPreservesArgvWithoutExpansion(@TempDir Path tempDir)
      throws IOException {
    Path script = tempDir.resolve("record-argv");
    Path output = tempDir.resolve("argv.bin");
    Path sideEffect = tempDir.resolve("expanded");
    Files.writeString(
        script,
        "#!/bin/sh\noutput=$1\nshift\nprintf '%s\\0' \"$@\" > \"$output\"\n",
        StandardCharsets.UTF_8);
    assertThat(script.toFile().setExecutable(true)).isTrue();
    List<String> arguments =
        List.of(
            "",
            "two words",
            "\ttabbed\t",
            "line one\nline two",
            "single'quote",
            "\"double quote\"",
            "*?[abc]",
            "safe; touch " + sideEffect,
            "`touch " + sideEffect + "`",
            "$(touch " + sideEffect + ")");

    ShellRunner hermeticShell =
        (wrapped, env, timeout) ->
            new DefaultShellRunner().run(List.of("/bin/sh", "-c", wrapped.get(4)), env, timeout);
    var runner = new LoginShellWrappingRunner(hermeticShell, ShellKind.SH);
    ProcessResult result =
        runner.run(
            command(script, output, arguments),
            Map.of(
                "HOME",
                tempDir.toString(),
                "ENV",
                "/dev/null",
                "BASH_ENV",
                "/dev/null",
                "ZDOTDIR",
                tempDir.toString()),
            Duration.ofSeconds(10));

    assertThat(result.exitCode()).isZero();
    assertThat(Files.readString(output, StandardCharsets.UTF_8))
        .isEqualTo(String.join("\0", arguments) + "\0");
    assertThat(sideEffect).doesNotExist();
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

  @Test
  void run_whenCommandStartsWithSudo_forwardsEveryOverloadWithoutWrapping() {
    var runner = new LoginShellWrappingRunner(delegate, ShellKind.ZSH);
    List<String> sudo = List.of("sudo", "dnf", "upgrade");
    Map<String, String> env = Map.of("LANG", "C");
    Duration timeout = Duration.ofSeconds(5);
    Optional<Path> workingDirectory = Optional.of(Path.of("/tmp"));
    Consumer<String> sink = ignored -> {};

    runner.run(sudo, env, timeout);
    runner.run(sudo, env, timeout, sink);
    runner.run(sudo, env, workingDirectory, timeout);
    runner.run(sudo, env, workingDirectory, timeout, sink);

    verify(delegate).run(sudo, env, timeout);
    verify(delegate).run(sudo, env, timeout, sink);
    verify(delegate).run(sudo, env, workingDirectory, timeout);
    verify(delegate).run(sudo, env, workingDirectory, timeout, sink);
  }

  private List<String> command(Path script, Path output, List<String> arguments) {
    var command = new java.util.ArrayList<String>();
    command.add(script.toString());
    command.add(output.toString());
    command.addAll(arguments);
    return List.copyOf(command);
  }
}
