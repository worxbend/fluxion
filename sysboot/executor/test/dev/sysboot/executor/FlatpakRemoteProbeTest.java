package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FlatpakRemoteProbeTest {

  @Mock private ShellRunner shellRunner;

  private FlatpakRemoteProbe probe;

  @BeforeEach
  void setUp() {
    probe = new FlatpakRemoteProbe(shellRunner);
  }

  @Test
  void supports_flatpakRemoteType_returnsTrue() {
    assertThat(probe.supports(ItemType.FLATPAK_REMOTE)).isTrue();
  }

  @Test
  void probe_whenRemotePresent_returnsInstalledByProbe() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "flathub\nfedora\n", "", Duration.ofMillis(20)));

    InstallationStatus status = probe.probe("flathub");

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
    assertThat(status.item()).isEqualTo("flathub");
  }

  @Test
  void probe_whenRemoteAbsent_returnsNotInstalled() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "fedora\n", "", Duration.ofMillis(20)));

    InstallationStatus status = probe.probe("flathub");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @Test
  void probe_whenFlatpakCommandFails_returnsUnknown() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(127, "", "flatpak not found", Duration.ofMillis(20)));

    InstallationStatus status = probe.probe("flathub");

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
  }
}
