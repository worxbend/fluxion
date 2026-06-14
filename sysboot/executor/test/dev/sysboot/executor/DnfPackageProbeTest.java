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
class DnfPackageProbeTest {

  @Mock private ShellRunner shellRunner;

  private DnfPackageProbe probe;

  @BeforeEach
  void setUp() {
    probe = new DnfPackageProbe(shellRunner);
  }

  @Test
  void supports_packageType_returnsTrue() {
    assertThat(probe.supports(ItemType.PACKAGE)).isTrue();
  }

  @Test
  void supports_nonPackageType_returnsFalse() {
    assertThat(probe.supports(ItemType.FLATPAK)).isFalse();
  }

  @Test
  void probe_whenRpmExitsZero_returnsInstalledByProbe() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "git-2.45.1-1.fc41.x86_64", "", Duration.ofMillis(100)));

    InstallationStatus status = probe.probe("git");

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
    var installed = (InstallationStatus.InstalledByProbe) status;
    assertThat(installed.item()).isEqualTo("git");
    assertThat(installed.detectedVersion()).isEqualTo("2.45.1-1.fc41.x86_64");
  }

  @Test
  void probe_whenRpmExitsOne_returnsNotInstalled() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(1, "package git is not installed", "", Duration.ofMillis(50)));

    InstallationStatus status = probe.probe("git");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
    assertThat(status.item()).isEqualTo("git");
  }

  @Test
  void probe_whenRpmExitsUnexpectedCode_returnsUnknown() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(127, "", "command not found: rpm", Duration.ofMillis(10)));

    InstallationStatus status = probe.probe("git");

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
  }
}
