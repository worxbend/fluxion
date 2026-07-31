package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BootstrapStateIdentityTest {

  @Test
  void withEntry_keepsSameItemKeyFromDifferentModules() {
    StateEntry core = entry("core", "shared");
    StateEntry desktop = entry("desktop", "shared");

    BootstrapState state =
        BootstrapState.empty("profile", "1.0.0").withEntry(core).withEntry(desktop);

    assertThat(state.entries()).containsExactly(core, desktop);
    assertThat(state.findEntry(new ModuleName("core"), "shared", ItemType.PACKAGE)).contains(core);
    assertThat(state.findEntry(new ModuleName("desktop"), "shared", ItemType.PACKAGE))
        .contains(desktop);
  }

  @Test
  void withoutItem_withCanonicalIdentity_keepsSameKeyFromOtherModules() {
    StateEntry core = entry("core", "shared");
    StateEntry desktop = entry("desktop", "shared");
    BootstrapState state =
        BootstrapState.empty("profile", "1.0.0").withEntry(core).withEntry(desktop);

    BootstrapState updated = state.withoutItem(new ModuleName("core"), "shared", ItemType.PACKAGE);

    assertThat(updated.entries()).containsExactly(desktop);
  }

  private StateEntry entry(String module, String item) {
    return new StateEntry(
        "profile",
        module,
        item,
        ItemType.PACKAGE,
        Instant.EPOCH,
        Optional.empty(),
        Optional.empty());
  }
}
