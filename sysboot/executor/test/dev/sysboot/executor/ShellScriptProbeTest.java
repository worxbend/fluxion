package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShellScriptProbeTest {

  @Mock private ShellRunner shellRunner;

  @Test
  void supports_shellScriptType_returnsTrue() {
    var probe = new ShellScriptProbe(shellRunner, Map.of());
    assertThat(probe.supports(ItemType.SHELL_SCRIPT)).isTrue();
  }

  @Test
  void probe_whenNoProbeCommandConfigured_returnsUnknown() {
    var probe = new ShellScriptProbe(shellRunner, Map.of());
    InstallationStatus status = probe.probe("/home/user/scripts/setup.sh");
    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
  }

  @Test
  void probe_whenProbeCommandSucceeds_returnsInstalledByProbe() {
    var scriptPath = "/home/user/scripts/install-sdkman.sh";
    var probeCmd = "test -d $HOME/.sdkman";
    var probe = new ShellScriptProbe(shellRunner, Map.of(scriptPath, probeCmd));

    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(50)));

    InstallationStatus status = probe.probe(scriptPath);

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
    assertThat(status.item()).isEqualTo(scriptPath);
  }

  @Test
  void probe_whenProbeCommandFails_returnsNotInstalled() {
    var scriptPath = "/home/user/scripts/install-sdkman.sh";
    var probeCmd = "test -d $HOME/.sdkman";
    var probe = new ShellScriptProbe(shellRunner, Map.of(scriptPath, probeCmd));

    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "", Duration.ofMillis(50)));

    InstallationStatus status = probe.probe(scriptPath);

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }
}
