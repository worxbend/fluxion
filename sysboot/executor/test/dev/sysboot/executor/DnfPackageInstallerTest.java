package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DnfPackageInstallerTest {

  @Mock private ShellRunner shellRunner;

  @Mock private SudoPasswordProvider sudoPasswordProvider;

  private DnfPackageInstaller installer;

  @BeforeEach
  void setUp() {
    installer = new DnfPackageInstaller(shellRunner, sudoPasswordProvider);
  }

  @Test
  void supports_whenKindIsDnf_returnsTrue() {
    assertThat(installer.supports(PackageManagerKind.DNF)).isTrue();
  }

  @Test
  void supports_whenKindIsNotDnf_returnsFalse() {
    assertThat(installer.supports(PackageManagerKind.PACMAN)).isFalse();
    assertThat(installer.supports(PackageManagerKind.APT)).isFalse();
  }

  @Test
  void install_whenShellRunnerSucceeds_returnsSuccess() {
    var packageName = new PackageName("git");
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "Installed!", "", Duration.ofSeconds(1)));

    StepResult result = installer.install(packageName);

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(((StepResult.Success) result).item()).isEqualTo("git");
  }

  @Test
  void install_whenShellRunnerFails_returnsFailure() {
    var packageName = new PackageName("nonexistent-pkg");
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "Package not found", "", Duration.ofSeconds(2)));

    StepResult result = installer.install(packageName);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    var failure = (StepResult.Failure) result;
    assertThat(failure.item()).isEqualTo("nonexistent-pkg");
    assertThat(failure.exitCode()).isEqualTo(1);
  }

  @Test
  void install_buildsCorrectDnfCommand() {
    var packageName = new PackageName("ripgrep");
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(500)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
    installer.install(packageName);

    verify(shellRunner).run(commandCaptor.capture(), eq(Map.of()), any(Duration.class));
    assertThat(commandCaptor.getValue()).containsExactly("sudo", "dnf", "install", "-y", "ripgrep");
  }

  @Test
  void install_passesEmptyEnvMap() {
    var packageName = new PackageName("git");
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(100)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> envCaptor = ArgumentCaptor.forClass(Map.class);
    installer.install(packageName);

    verify(shellRunner).run(any(), envCaptor.capture(), any());
    assertThat(envCaptor.getValue()).isEmpty();
  }
}
