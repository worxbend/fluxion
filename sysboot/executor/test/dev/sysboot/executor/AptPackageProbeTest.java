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
class AptPackageProbeTest {

  @Mock private ShellRunner shellRunner;

  private AptPackageProbe probe;

  @BeforeEach
  void setUp() {
    probe = new AptPackageProbe(shellRunner);
  }

  @Test
  void supports_packageType_returnsTrue() {
    assertThat(probe.supports(ItemType.PACKAGE)).isTrue();
  }

  @Test
  void probe_whenInstalledStatusLine_returnsInstalledByProbe() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(0, "install ok installed\t2.43.0", "", Duration.ofMillis(50)));

    InstallationStatus status = probe.probe("git");

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
    var installed = (InstallationStatus.InstalledByProbe) status;
    assertThat(installed.item()).isEqualTo("git");
    assertThat(installed.detectedVersion()).isEqualTo("2.43.0");
  }

  @Test
  void probe_whenNotInstalledStatusLine_returnsNotInstalled() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "unknown ok not-installed\t", "", Duration.ofMillis(50)));

    InstallationStatus status = probe.probe("git");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @Test
  void probe_whenDpkgQueryFails_returnsNotInstalled() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(1, "", "No packages found matching git", Duration.ofMillis(30)));

    InstallationStatus status = probe.probe("git");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }
}
