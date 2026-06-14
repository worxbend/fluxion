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
class FlatpakProbeTest {

  @Mock private ShellRunner shellRunner;

  private FlatpakProbe probe;

  @BeforeEach
  void setUp() {
    probe = new FlatpakProbe(shellRunner);
  }

  @Test
  void supports_flatpakType_returnsTrue() {
    assertThat(probe.supports(ItemType.FLATPAK)).isTrue();
  }

  @Test
  void supports_packageType_returnsFalse() {
    assertThat(probe.supports(ItemType.PACKAGE)).isFalse();
  }

  @Test
  void probe_whenAppIdPresentInListing_returnsInstalledByProbe() {
    String listing = "com.spotify.Client\norg.telegram.desktop\ncom.obsproject.Studio\n";
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, listing, "", Duration.ofMillis(200)));

    InstallationStatus status = probe.probe("com.spotify.Client");

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
    assertThat(status.item()).isEqualTo("com.spotify.Client");
  }

  @Test
  void probe_whenAppIdAbsentFromListing_returnsNotInstalled() {
    String listing = "org.telegram.desktop\ncom.obsproject.Studio\n";
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, listing, "", Duration.ofMillis(200)));

    InstallationStatus status = probe.probe("com.spotify.Client");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @Test
  void probe_whenFlatpakCommandFails_returnsUnknown() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(127, "", "flatpak: command not found", Duration.ofMillis(10)));

    InstallationStatus status = probe.probe("com.spotify.Client");

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
  }
}
