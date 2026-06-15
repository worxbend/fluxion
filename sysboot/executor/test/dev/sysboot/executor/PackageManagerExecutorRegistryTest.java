package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PackageManagerExecutorRegistryTest {

  private static final PackageManagerExecutor DNF_EXECUTOR =
      new PackageManagerExecutor() {
        @Override
        public boolean supports(PackageManagerKind kind) {
          return kind == PackageManagerKind.DNF;
        }

        @Override
        public List<String> installCommand(PackageName packageName) {
          return List.of("sudo", "dnf", "install", "-y", packageName.value());
        }

        @Override
        public StepResult install(PackageName packageName) {
          return new StepResult.Success(packageName.value(), Duration.ZERO);
        }
      };

  private static final PackageManagerExecutor PACMAN_EXECUTOR =
      new PackageManagerExecutor() {
        @Override
        public boolean supports(PackageManagerKind kind) {
          return kind == PackageManagerKind.PACMAN;
        }

        @Override
        public List<String> installCommand(PackageName packageName) {
          return List.of("sudo", "pacman", "-S", "--noconfirm", packageName.value());
        }

        @Override
        public StepResult install(PackageName packageName) {
          return new StepResult.Success(packageName.value(), Duration.ZERO);
        }
      };

  @Test
  void forKind_whenDnfRegistered_returnsDnfExecutor() {
    var registry = new PackageManagerExecutorRegistry(List.of(DNF_EXECUTOR, PACMAN_EXECUTOR));

    PackageManagerExecutor result = registry.forKind(PackageManagerKind.DNF);

    assertThat(result).isSameAs(DNF_EXECUTOR);
  }

  @Test
  void forKind_whenPacmanRegistered_returnsPacmanExecutor() {
    var registry = new PackageManagerExecutorRegistry(List.of(DNF_EXECUTOR, PACMAN_EXECUTOR));

    PackageManagerExecutor result = registry.forKind(PackageManagerKind.PACMAN);

    assertThat(result).isSameAs(PACMAN_EXECUTOR);
  }

  @Test
  void forKind_whenKindNotRegistered_throwsUnsupportedPackageManagerException() {
    var registry = new PackageManagerExecutorRegistry(List.of(DNF_EXECUTOR));

    assertThatThrownBy(() -> registry.forKind(PackageManagerKind.APT))
        .isInstanceOf(UnsupportedPackageManagerException.class)
        .hasMessageContaining("APT");
  }

  @Test
  void constructor_whenNullList_throwsNullPointerException() {
    assertThatThrownBy(() -> new PackageManagerExecutorRegistry(null))
        .isInstanceOf(NullPointerException.class);
  }
}
