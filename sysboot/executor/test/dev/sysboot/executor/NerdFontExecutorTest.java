package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NerdFontExecutorTest {

  @Mock private ShellRunner runner;
  private final Path installer = Path.of("/tmp/nerdfont-install");

  private NerdFontModule module() {
    var config =
        new NerdFontConfig(
            "v3.2.1",
            Path.of("~/.local/share/fonts/nerd"),
            true,
            List.of("JetBrainsMono", "FiraCode"));
    return new NerdFontModule(
        new ModuleName("nerd-fonts"), "v1.0.5", "nerdfetch", config, Optional.empty());
  }

  @Test
  void execute_exitZero_returnsSuccess() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "Fonts installed", "", Duration.ofSeconds(20)));

    var executor = executor();
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void execute_exitNonZero_returnsFailure() {
    when(runner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "download failed", Duration.ofSeconds(5)));

    var executor = executor();
    StepResult result = executor.execute(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
  }

  @Test
  void execute_commandIncludesConfigFlag() {
    when(runner.run(any(), any(), any())).thenReturn(new ProcessResult(0, "", "", Duration.ZERO));

    var executor = executor();
    executor.execute(module());

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(runner).run(captor.capture(), any(), any());

    List<String> cmd = captor.getValue();
    assertThat(cmd.getFirst()).isEqualTo(installer.toString());
    assertThat(cmd).contains("--config");
    int configIdx = cmd.indexOf("--config");
    assertThat(cmd.get(configIdx + 1)).endsWith(".yaml");
  }

  private NerdFontExecutor executor() {
    return new NerdFontExecutor(runner, ignored -> installer);
  }
}
