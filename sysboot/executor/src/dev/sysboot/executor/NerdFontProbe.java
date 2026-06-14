package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class NerdFontProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

  private final ShellRunner shellRunner;

  public NerdFontProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.NERD_FONT;
  }

  @Override
  public InstallationStatus probe(String itemKey) {
    // itemKey is the first font family name; check fc-list for it
    var result =
        shellRunner.run(
            List.of("/bin/sh", "-c", "fc-list | grep -qi '" + itemKey + "'"),
            Map.of(),
            PROBE_TIMEOUT);

    if (result.exitCode() == 0) {
      return new InstallationStatus.InstalledByProbe(itemKey, null);
    }
    if (result.exitCode() == 1) {
      return new InstallationStatus.NotInstalled(itemKey);
    }
    return new InstallationStatus.Unknown(itemKey, "fc-list probe failed: " + result.stderr());
  }
}
