package dev.sysboot.executor;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
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
    try {
      var result = shellRunner.run(List.of("fc-list", ":", "family"), Map.of(), PROBE_TIMEOUT);

      if (result.exitCode() != 0) {
        return new InstallationStatus.Unknown(itemKey, "fc-list probe failed: " + result.stderr());
      }
      if (containsFamily(result.stdout(), itemKey)) {
        return new InstallationStatus.InstalledByProbe(itemKey, null);
      }
      return new InstallationStatus.NotInstalled(itemKey);
    } catch (ShellExecutionException exception) {
      return unavailable(itemKey, exception);
    }
  }

  private InstallationStatus unavailable(String itemKey, ShellExecutionException exception) {
    if (Thread.currentThread().isInterrupted()
        || exception.getCause() instanceof InterruptedException) {
      Thread.currentThread().interrupt();
      throw exception;
    }
    return new InstallationStatus.Unknown(
        itemKey, "fc-list probe unavailable: " + exception.getMessage());
  }

  private boolean containsFamily(String output, String expectedFamily) {
    String expected = expectedFamily.toLowerCase(Locale.ROOT);
    return output
        .lines()
        .flatMap(line -> Arrays.stream(line.split(",")))
        .map(String::strip)
        .map(family -> family.toLowerCase(Locale.ROOT))
        .anyMatch(family -> family.contains(expected));
  }
}
