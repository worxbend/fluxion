package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The orchestrator used to build a fresh executor for every item and never read the ones handed to
 * its constructor, which meant a stubbed executor was silently ignored. These tests pin the fix.
 */
class PhaseExecutorsTest {

  private final ShellRunner primaryRunner = new NoopShellRunner();

  @Test
  void theInjectedExecutorsAreUsedForThePrimaryRunner() {
    var injectedDotbot = mock(DotbotExecutor.class);
    var injectedNerdFont = mock(NerdFontExecutor.class);
    var registry = registryWith(injectedDotbot, injectedNerdFont);

    PhaseExecutors executors = registry.forRunner(primaryRunner);

    assertThat(executors.dotbot()).isSameAs(injectedDotbot);
    assertThat(executors.nerdFont()).isSameAs(injectedNerdFont);
  }

  @Test
  void aDifferentRunnerGetsItsOwnExecutors() {
    var injectedDotbot = mock(DotbotExecutor.class);
    var registry = registryWith(injectedDotbot, mock(NerdFontExecutor.class));
    ShellRunner loginShellRunner = new NoopShellRunner();

    PhaseExecutors executors = registry.forRunner(loginShellRunner);

    assertThat(executors.dotbot()).isNotSameAs(injectedDotbot);
  }

  @Test
  void executorsForTheSameRunnerAreBuiltOnceAndReused() {
    var registry = registryWith(mock(DotbotExecutor.class), mock(NerdFontExecutor.class));
    ShellRunner loginShellRunner = new NoopShellRunner();

    assertThat(registry.forRunner(loginShellRunner)).isSameAs(registry.forRunner(loginShellRunner));
  }

  private PhaseExecutors.Registry registryWith(DotbotExecutor dotbot, NerdFontExecutor nerdFont) {
    return new PhaseExecutors.Registry(
        primaryRunner,
        PhaseExecutors.injected(
            primaryRunner,
            mock(ShellScriptExecutor.class),
            dotbot,
            mock(DefaultShellExecutor.class),
            mock(OhMyZshExecutor.class),
            mock(ToolchainExecutor.class),
            nerdFont,
            mock(ShellReloadExecutor.class)));
  }

  private static final class NoopShellRunner implements ShellRunner {
    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
  }
}
