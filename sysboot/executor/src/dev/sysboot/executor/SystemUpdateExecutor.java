package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemUpdateModule;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs a full system update for the host's package manager.
 *
 * <p>The subtlety is exit codes: {@code dnf check-update} returns 100 to mean "updates are
 * available", which is a successful outcome and not a failure. Treating every non-zero exit as a
 * failure would make a refresh step fail on exactly the machines that had something to install.
 */
public final class SystemUpdateExecutor {

  private static final Duration DEFAULT_TIMEOUT = Duration.ofHours(2);

  private final ShellRunner shellRunner;

  public SystemUpdateExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(SystemUpdateModule module) {
    Duration timeout = module.timeout().orElse(DEFAULT_TIMEOUT);
    for (List<String> command : commands(module)) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      ProcessResult result = shellRunner.run(command, Map.of(), timeout);
      if (!succeeded(module, result)) {
        return new StepResult.Failure(
            module.itemKey(),
            String.join(" ", command) + ": " + detail(result),
            result.exitCode(),
            result.elapsed());
      }
    }
    return new StepResult.Success(module.itemKey(), Duration.ZERO);
  }

  public List<String> commandPreview(SystemUpdateModule module) {
    return commands(module).stream().flatMap(List::stream).toList();
  }

  private boolean succeeded(SystemUpdateModule module, ProcessResult result) {
    if (result.isSuccess()) {
      return true;
    }
    // dnf/yum check-update: 100 means "updates available", 0 means "none". Both are fine.
    return module.refreshOnly()
        && module.packageManager() == PackageManagerKind.DNF
        && result.exitCode() == 100;
  }

  private List<List<String>> commands(SystemUpdateModule module) {
    return switch (module.packageManager()) {
      case ZYPPER -> zypper(module);
      case DNF -> dnf(module);
      case PACMAN, PARU, YAY -> pacman(module);
      case APT -> apt(module);
      case CARGO, FLATPAK ->
          throw new IllegalArgumentException(
              "system-update does not apply to "
                  + module.packageManager().name().toLowerCase(java.util.Locale.ROOT)
                  + "; use tool-packages or a flatpak step instead");
    };
  }

  private List<List<String>> zypper(SystemUpdateModule module) {
    List<String> refresh = List.of("sudo", "zypper", "--non-interactive", "refresh");
    if (module.refreshOnly()) {
      return List.of(refresh);
    }
    // Tumbleweed is a rolling release, where `update` is the wrong verb and `dup` is required.
    String verb = module.distUpgrade() ? "dup" : "update";
    return List.of(refresh, List.of("sudo", "zypper", "--non-interactive", verb));
  }

  private List<List<String>> dnf(SystemUpdateModule module) {
    if (module.refreshOnly()) {
      return List.of(List.of("sudo", "dnf", "check-update", "--refresh"));
    }
    return List.of(List.of("sudo", "dnf", "upgrade", "-y", "--refresh"));
  }

  private List<List<String>> pacman(SystemUpdateModule module) {
    if (module.refreshOnly()) {
      return List.of(List.of("sudo", "pacman", "-Sy", "--noconfirm"));
    }
    return List.of(List.of("sudo", "pacman", "-Syu", "--noconfirm"));
  }

  private List<List<String>> apt(SystemUpdateModule module) {
    List<String> update = List.of("sudo", "apt-get", "update");
    if (module.refreshOnly()) {
      return List.of(update);
    }
    String verb = module.distUpgrade() ? "full-upgrade" : "upgrade";
    return List.of(update, List.of("sudo", "apt-get", verb, "-y"));
  }

  private String detail(ProcessResult result) {
    String text = result.stderr().isBlank() ? result.stdout() : result.stderr();
    return text.isBlank() ? "exit " + result.exitCode() : text.strip();
  }
}
