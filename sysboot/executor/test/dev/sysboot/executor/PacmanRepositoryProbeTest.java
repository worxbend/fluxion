package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PacmanRepositoryProbeTest {

  @Mock private ShellRunner shellRunner;

  @Test
  void supports_pacmanRepositoryType_returnsTrue() {
    assertThat(new PacmanRepositoryProbe(shellRunner).supports(ItemType.PACMAN_REPOSITORY))
        .isTrue();
  }

  @Test
  void probe_whenRepositoryExists_returnsInstalledByProbe() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(5)));

    InstallationStatus status = new PacmanRepositoryProbe(shellRunner).probe("chaotic-aur");

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @Test
  void probe_whenRepositoryIsMissing_returnsNotInstalled() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "", Duration.ofMillis(5)));

    InstallationStatus status = new PacmanRepositoryProbe(shellRunner).probe("chaotic-aur");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }
}
