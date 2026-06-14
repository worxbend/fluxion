package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SudoPasswordProvider;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AptPackageInstallerTest {

  @Mock private ShellRunner shellRunner;

  @Mock private SudoPasswordProvider sudoPasswordProvider;

  private AptPackageInstaller installer;

  @BeforeEach
  void setUp() {
    installer = new AptPackageInstaller(shellRunner, sudoPasswordProvider);
  }

  @Test
  void supports_whenKindIsApt_returnsTrue() {
    assertThat(installer.supports(PackageManagerKind.APT)).isTrue();
  }

  @Test
  void supports_whenKindIsNotApt_returnsFalse() {
    assertThat(installer.supports(PackageManagerKind.DNF)).isFalse();
  }

  @Test
  void install_buildsCorrectAptCommand() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(500)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
    installer.install(new PackageName("curl"));

    verify(shellRunner).run(commandCaptor.capture(), any(), any());
    assertThat(commandCaptor.getValue())
        .containsExactly("sudo", "apt-get", "install", "-y", "curl");
  }

  @Test
  void install_whenExitCodeNonZero_returnsFailure() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(100, "E: Unable to locate package", "", Duration.ofSeconds(1)));

    StepResult result = installer.install(new PackageName("nonexistent"));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).exitCode()).isEqualTo(100);
  }
}
