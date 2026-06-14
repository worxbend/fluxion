package dev.sysboot.cli;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.InstallationStatus;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "status", description = "Show installation status for all items in a profile")
public final class StatusCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Option(
      names = {"--profile"},
      description = "Profile name",
      paramLabel = "PROFILE",
      defaultValue = "default")
  private String profile;

  @Override
  public void run() {
    var context = ApplicationContext.create(true, profile, false, false);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());

    System.out.printf("%-45s  %-15s  %s%n", "Item", "Type", "Status");
    System.out.println("-".repeat(80));

    Map<String, InstallationStatus> results =
        context.parallelProbeRunner().probeAll(config.modules(), ignored -> {});

    if (results.isEmpty()) {
      System.out.println("(no items found)");
      return;
    }

    results.forEach(
        (key, status) -> {
          String statusLabel =
              switch (status) {
                case InstallationStatus.InstalledByProbe p ->
                    "installed"
                        + (p.detectedVersion() != null ? " (" + p.detectedVersion() + ")" : "");
                case InstallationStatus.InstalledFromState s ->
                    "from-state (" + s.installedAt() + ")";
                case InstallationStatus.NotInstalled ignored -> "not installed";
                case InstallationStatus.Unknown u -> "unknown: " + u.reason();
              };
          System.out.printf("%-45s  %-15s  %s%n", truncate(key, 45), "", statusLabel);
        });
  }

  private String truncate(String s, int max) {
    return s.length() <= max ? s : s.substring(0, max - 3) + "...";
  }
}
