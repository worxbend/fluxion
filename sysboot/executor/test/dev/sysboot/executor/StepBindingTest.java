package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitConfigScope;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.SystemUpdateModule;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The binding table replaced twenty-eight hand-written switch arms. Its value depends on staying
 * complete and on not claiming kinds that need bespoke handling, so both are asserted.
 */
class StepBindingTest {

  @Test
  void aBoundKindResolvesToItsItemTypeAndKey() {
    var module =
        new GitConfigModule(
            new ModuleName("git"), GitConfigScope.GLOBAL, Map.of("user.name", "x"), false);

    StepBinding binding = StepBinding.find(module).orElseThrow();

    assertThat(binding.itemType()).isEqualTo(ItemType.GIT_CONFIG);
    assertThat(binding.itemKey(module)).isEqualTo("git");
  }

  @Test
  void aKindWithItsOwnItemKeyUsesIt() {
    var module =
        new SystemUpdateModule(
            new ModuleName("update"),
            PackageManagerKind.ZYPPER,
            true,
            false,
            Optional.empty(),
            false);

    assertThat(StepBinding.find(module).orElseThrow().itemKey(module))
        .isEqualTo("zypper-system-update");
  }

  @Test
  void kindsThatNeedBespokeHandlingAreDeliberatelyNotBound() {
    // Packages loop per item through their own executor registry. Binding them here would collapse
    // sixty packages into one item and lose per-package failure isolation.
    BootstrapModule packages =
        new PackageModule(
            new ModuleName("core"),
            PackageManagerKind.DNF,
            List.of(new dev.sysboot.core.PackageName("git")),
            false);

    assertThat(StepBinding.find(packages)).isEmpty();
  }
}
