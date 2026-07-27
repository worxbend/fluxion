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
class CargoPackageInstallerTest {

  @Mock private ShellRunner shellRunner;

  @Mock private SudoPasswordProvider sudoPasswordProvider;

  private CargoPackageInstaller installer;

  @BeforeEach
  void setUp() {
    installer = new CargoPackageInstaller(shellRunner, sudoPasswordProvider);
  }

  @Test
  void supports_whenKindIsCargo_returnsTrue() {
    assertThat(installer.supports(PackageManagerKind.CARGO)).isTrue();
  }

  @Test
  void supports_whenKindIsNotCargo_returnsFalse() {
    assertThat(installer.supports(PackageManagerKind.DNF)).isFalse();
  }

  @Test
  void install_buildsCargoInstallCommand() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(500)));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> commandCaptor = ArgumentCaptor.forClass(List.class);
    installer.install(new PackageName("cargo-binstall"));

    verify(shellRunner).run(commandCaptor.capture(), any(), any());
    assertThat(commandCaptor.getValue()).containsExactly("cargo", "install", "cargo-binstall");
  }

  @Test
  void install_whenExitCodeNonZero_returnsFailure() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(101, "", "failed", Duration.ofSeconds(1)));

    StepResult result = installer.install(new PackageName("broken-crate"));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).exitCode()).isEqualTo(101);
  }
}
