package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemSettingModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Applies timedatectl / hostnamectl / localectl settings, skipping ones already in place. */
public final class SystemSettingExecutor {

  private static final Duration TIMEOUT = Duration.ofSeconds(60);

  private final ShellRunner shellRunner;

  public SystemSettingExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(SystemSettingModule module) {
    var failures = new ArrayList<String>();
    for (List<String> command : pendingCommands(module)) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      ProcessResult result = shellRunner.run(command, Map.of(), TIMEOUT);
      if (!result.isSuccess()) {
        failures.add(String.join(" ", command) + ": " + StepOutcome.detail(result));
      }
    }
    return StepOutcome.of(module.name(), failures, module.continueOnError());
  }

  /** True when every requested setting already holds. */
  public boolean alreadySatisfied(SystemSettingModule module) {
    return pendingCommands(module).isEmpty();
  }

  public List<String> commandPreview(SystemSettingModule module) {
    return allCommands(module).stream().flatMap(List::stream).toList();
  }

  /** Only the commands whose setting is not already in place, so a rerun is a no-op. */
  private List<List<String>> pendingCommands(SystemSettingModule module) {
    var pending = new ArrayList<List<String>>();
    module
        .localRtc()
        .filter(desired -> !desired.equals(showBoolean("timedatectl", "LocalRTC").orElse(null)))
        .ifPresent(desired -> pending.add(timedatectl("set-local-rtc", desired ? "1" : "0")));
    module
        .ntp()
        .filter(desired -> !desired.equals(showBoolean("timedatectl", "NTP").orElse(null)))
        .ifPresent(desired -> pending.add(timedatectl("set-ntp", desired ? "true" : "false")));
    module
        .timezone()
        .filter(desired -> !desired.equals(show("timedatectl", "Timezone").orElse(null)))
        .ifPresent(desired -> pending.add(timedatectl("set-timezone", desired)));
    module
        .hostname()
        .filter(desired -> !desired.equals(show("hostnamectl", "StaticHostname").orElse(null)))
        .ifPresent(desired -> pending.add(List.of("sudo", "hostnamectl", "set-hostname", desired)));
    module
        .locale()
        .forEach(
            (key, value) -> {
              if (!localeMatches(key, value)) {
                pending.add(List.of("sudo", "localectl", "set-locale", key + "=" + value));
              }
            });
    return List.copyOf(pending);
  }

  private List<List<String>> allCommands(SystemSettingModule module) {
    var all = new ArrayList<List<String>>();
    module.localRtc().ifPresent(v -> all.add(timedatectl("set-local-rtc", v ? "1" : "0")));
    module.ntp().ifPresent(v -> all.add(timedatectl("set-ntp", v ? "true" : "false")));
    module.timezone().ifPresent(v -> all.add(timedatectl("set-timezone", v)));
    module.hostname().ifPresent(v -> all.add(List.of("sudo", "hostnamectl", "set-hostname", v)));
    module
        .locale()
        .forEach((k, v) -> all.add(List.of("sudo", "localectl", "set-locale", k + "=" + v)));
    return List.copyOf(all);
  }

  private List<String> timedatectl(String verb, String value) {
    return List.of("sudo", "timedatectl", verb, value);
  }

  private boolean localeMatches(String key, String value) {
    ProcessResult result = shellRunner.run(List.of("localectl", "status"), Map.of(), TIMEOUT);
    return result.isSuccess() && result.stdout().contains(key + "=" + value);
  }

  private Optional<Boolean> showBoolean(String tool, String property) {
    return show(tool, property)
        .map(value -> value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("true"));
  }

  private Optional<String> show(String tool, String property) {
    ProcessResult result =
        shellRunner.run(
            List.of(tool, "show", "--property=" + property, "--value"), Map.of(), TIMEOUT);
    if (!result.isSuccess()) {
      return Optional.empty();
    }
    String value = result.stdout().strip();
    return value.isBlank() ? Optional.empty() : Optional.of(value);
  }
}
