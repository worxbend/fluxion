package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class DefaultShellProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

  private final ShellRunner shellRunner;

  public DefaultShellProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.DEFAULT_SHELL;
  }

  @Override
  public InstallationStatus probe(String shellPath) {
    String user = System.getProperty("user.name");
    var result = shellRunner.run(List.of("getent", "passwd", user), Map.of(), PROBE_TIMEOUT);

    if (result.exitCode() != 0) {
      return new InstallationStatus.Unknown(shellPath, "getent failed: " + result.stderr());
    }

    // Format: user:x:1000:1000::/home/user:/bin/zsh — field 7 (index 6) is default shell
    String[] fields = result.stdout().strip().split(":");
    if (fields.length < 7) {
      return new InstallationStatus.Unknown(
          shellPath, "Unexpected getent output: " + result.stdout());
    }
    String currentShell = fields[6];
    return currentShell.equals(shellPath)
        ? new InstallationStatus.InstalledByProbe(shellPath, currentShell)
        : new InstallationStatus.NotInstalled(shellPath);
  }
}
