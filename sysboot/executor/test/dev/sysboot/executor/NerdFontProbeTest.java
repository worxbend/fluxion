package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NerdFontProbeTest {

  @Mock private ShellRunner shellRunner;

  @Test
  void supports_whenItemIsNerdFont_returnsTrue() {
    assertThat(new NerdFontProbe(shellRunner).supports(ItemType.NERD_FONT)).isTrue();
  }

  @Test
  void probe_alwaysUsesExactStructuredFcListArgv() {
    when(shellRunner.run(eq(command()), eq(Map.of()), eq(timeout())))
        .thenReturn(success("JetBrainsMono Nerd Font"));

    new NerdFontProbe(shellRunner).probe("$(touch /tmp/never-run)");

    verify(shellRunner).run(eq(command()), eq(Map.of()), eq(timeout()));
  }

  @Test
  void probe_whenFamilyMatchesIgnoringCase_returnsInstalled() {
    when(shellRunner.run(eq(command()), eq(Map.of()), eq(timeout())))
        .thenReturn(success("Noto Sans, JetBrainsMono Nerd Font\nDejaVu Sans"));

    InstallationStatus status = new NerdFontProbe(shellRunner).probe("jetbrainsmono");

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @Test
  void probe_whenAdversarialFamilyTextDoesNotMatch_doesNotInterpretIt(@TempDir Path tempDir) {
    Path sideEffect = tempDir.resolve("expanded");
    String output = "$(touch " + sideEffect + "), `touch " + sideEffect + "`";
    when(shellRunner.run(eq(command()), eq(Map.of()), eq(timeout()))).thenReturn(success(output));

    InstallationStatus status = new NerdFontProbe(shellRunner).probe("FiraCode");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
    assertThat(sideEffect).doesNotExist();
  }

  @Test
  void probe_whenFcListExitsNonzero_returnsUnknown() {
    when(shellRunner.run(eq(command()), eq(Map.of()), eq(timeout())))
        .thenReturn(new ProcessResult(2, "", "fontconfig error", Duration.ofMillis(2)));

    InstallationStatus status = new NerdFontProbe(shellRunner).probe("FiraCode");

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
  }

  @Test
  void probe_whenFcListTimesOut_returnsUnknown() {
    when(shellRunner.run(eq(command()), eq(Map.of()), eq(timeout())))
        .thenReturn(new ProcessResult(124, "", "Process timed out", timeout()));

    InstallationStatus status = new NerdFontProbe(shellRunner).probe("FiraCode");

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
  }

  @Test
  void probe_whenFcListExecutableIsMissing_returnsUnknown() {
    when(shellRunner.run(eq(command()), eq(Map.of()), eq(timeout())))
        .thenThrow(new ShellExecutionException("Failed to start process: fc-list"));

    InstallationStatus status = new NerdFontProbe(shellRunner).probe("FiraCode");

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
  }

  @Test
  void probe_whenExecutionIsInterrupted_rethrowsAndPreservesInterrupt() {
    var failure =
        new ShellExecutionException("Process interrupted: fc-list", new InterruptedException());
    when(shellRunner.run(eq(command()), eq(Map.of()), eq(timeout()))).thenThrow(failure);

    try {
      assertThatThrownBy(() -> new NerdFontProbe(shellRunner).probe("FiraCode")).isSameAs(failure);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static List<String> command() {
    return List.of("fc-list", ":", "family");
  }

  private static Duration timeout() {
    return Duration.ofSeconds(15);
  }

  private static ProcessResult success(String output) {
    return new ProcessResult(0, output, "", Duration.ofMillis(2));
  }
}
