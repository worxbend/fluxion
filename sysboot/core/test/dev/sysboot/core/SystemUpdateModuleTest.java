package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SystemUpdateModuleTest {

  @Test
  void rejectsManagersWithoutSystemUpdateSemantics() {
    for (PackageManagerKind kind : List.of(PackageManagerKind.CARGO, PackageManagerKind.FLATPAK)) {
      assertThatThrownBy(() -> module(kind))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("system-update does not support");
    }
  }

  @Test
  void acceptsSupportedSystemPackageManagers() {
    for (PackageManagerKind kind :
        List.of(
            PackageManagerKind.DNF,
            PackageManagerKind.PACMAN,
            PackageManagerKind.PARU,
            PackageManagerKind.YAY,
            PackageManagerKind.APT,
            PackageManagerKind.ZYPPER)) {
      assertThatCode(() -> module(kind)).doesNotThrowAnyException();
    }
  }

  private SystemUpdateModule module(PackageManagerKind kind) {
    return new SystemUpdateModule(
        new ModuleName("update"), kind, false, false, Optional.empty(), false);
  }
}
