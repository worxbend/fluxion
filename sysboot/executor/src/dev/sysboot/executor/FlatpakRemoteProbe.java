package dev.sysboot.executor;

import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public final class FlatpakRemoteProbe implements InstalledProbe {

  private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(15);

  private final ShellRunner shellRunner;

  public FlatpakRemoteProbe(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  @Override
  public boolean supports(ItemType itemType) {
    return itemType == ItemType.FLATPAK_REMOTE;
  }

  @Override
  public InstallationStatus probe(String remote) {
    var result =
        shellRunner.run(List.of("flatpak", "remotes", "--columns=name"), Map.of(), PROBE_TIMEOUT);
    if (result.exitCode() != 0) {
      return new InstallationStatus.Unknown(
          remote,
          "flatpak remotes failed (exit %d): %s".formatted(result.exitCode(), result.stderr()));
    }
    boolean found = result.stdout().lines().map(String::strip).anyMatch(remote::equals);
    return found
        ? new InstallationStatus.InstalledByProbe(remote, null)
        : new InstallationStatus.NotInstalled(remote);
  }

  @Override
  public InstallationStatus probe(ModuleItem item) {
    if (item.sourceSetup().orElse(null) instanceof FlatpakRemoteSourceSetup setup) {
      return configuredProbe(item, setup);
    }
    return probe(item.key());
  }

  private InstallationStatus configuredProbe(ModuleItem item, FlatpakRemoteSourceSetup setup) {
    if (!item.key().equals(setup.remote())) {
      return new InstallationStatus.NotInstalled(item.key());
    }
    var command = new java.util.ArrayList<String>();
    command.add("flatpak");
    command.add(setup.system() ? "--system" : "--user");
    command.add("remotes");
    command.add("--columns=name,url");
    var result = shellRunner.run(List.copyOf(command), Map.of(), PROBE_TIMEOUT);
    if (result.exitCode() != 0) {
      return new InstallationStatus.Unknown(
          item.key(),
          "flatpak remotes failed (exit %d): %s".formatted(result.exitCode(), result.stderr()));
    }
    boolean found =
        result
            .stdout()
            .lines()
            .map(String::strip)
            .map(line -> line.split("\\s+", 2))
            .anyMatch(
                columns ->
                    columns.length == 2
                        && setup.remote().equals(columns[0])
                        && setup.url().toString().equals(columns[1]));
    return found
        ? new InstallationStatus.InstalledByProbe(item.key(), null)
        : new InstallationStatus.NotInstalled(item.key());
  }
}
