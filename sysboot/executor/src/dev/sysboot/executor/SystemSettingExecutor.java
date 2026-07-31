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
    for (String key : module.itemKeys()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      StepResult result = executeItem(module, key);
      if (result instanceof StepResult.Failure failure) {
        failures.add(key + ": " + failure.errorMessage());
      }
    }
    return StepOutcome.of(module.name(), failures, module.continueOnError());
  }

  StepResult executeItem(SystemSettingModule module, String key) {
    Optional<List<String>> pending = pendingCommand(module, key);
    if (pending.isEmpty()) {
      return new StepResult.Success(key, Duration.ZERO);
    }
    ProcessResult result = shellRunner.run(pending.orElseThrow(), Map.of(), TIMEOUT);
    return result.isSuccess()
        ? new StepResult.Success(key, result.elapsed())
        : new StepResult.Failure(
            key, StepOutcome.detail(result), result.exitCode(), result.elapsed());
  }

  /** True when every requested setting already holds. */
  public boolean alreadySatisfied(SystemSettingModule module) {
    return pendingCommands(module).isEmpty();
  }

  public List<String> commandPreview(SystemSettingModule module) {
    return module.itemKeys().stream().flatMap(key -> commandPreview(module, key).stream()).toList();
  }

  List<String> commandPreview(SystemSettingModule module, String key) {
    return command(module, key);
  }

  /** Only the commands whose setting is not already in place, so a rerun is a no-op. */
  private List<List<String>> pendingCommands(SystemSettingModule module) {
    return module.itemKeys().stream()
        .map(key -> pendingCommand(module, key))
        .flatMap(Optional::stream)
        .toList();
  }

  private Optional<List<String>> pendingCommand(SystemSettingModule module, String key) {
    boolean satisfied =
        switch (key) {
          case "localRtc" ->
              module
                  .localRtc()
                  .orElseThrow()
                  .equals(showBoolean("timedatectl", "LocalRTC").orElse(null));
          case "ntp" ->
              module.ntp().orElseThrow().equals(showBoolean("timedatectl", "NTP").orElse(null));
          case "timezone" ->
              module.timezone().orElseThrow().equals(show("timedatectl", "Timezone").orElse(null));
          case "hostname" ->
              module
                  .hostname()
                  .orElseThrow()
                  .equals(show("hostnamectl", "StaticHostname").orElse(null));
          default -> {
            String localeKey = localeKey(key);
            yield localeMatches(localeKey, module.locale().get(localeKey));
          }
        };
    return satisfied ? Optional.empty() : Optional.of(command(module, key));
  }

  private List<String> command(SystemSettingModule module, String key) {
    return switch (key) {
      case "localRtc" -> timedatectl("set-local-rtc", module.localRtc().orElseThrow() ? "1" : "0");
      case "ntp" -> timedatectl("set-ntp", module.ntp().orElseThrow() ? "true" : "false");
      case "timezone" -> timedatectl("set-timezone", module.timezone().orElseThrow());
      case "hostname" ->
          List.of("sudo", "hostnamectl", "set-hostname", module.hostname().orElseThrow());
      default -> {
        String localeKey = localeKey(key);
        yield List.of(
            "sudo", "localectl", "set-locale", localeKey + "=" + module.locale().get(localeKey));
      }
    };
  }

  private String localeKey(String itemKey) {
    if (!itemKey.startsWith("locale:") || itemKey.length() == "locale:".length()) {
      throw new IllegalArgumentException("unknown system-setting item key: " + itemKey);
    }
    return itemKey.substring("locale:".length());
  }

  private List<String> timedatectl(String verb, String value) {
    return List.of("sudo", "timedatectl", verb, value);
  }

  private boolean localeMatches(String key, String value) {
    ProcessResult result = shellRunner.run(List.of("localectl", "status"), Map.of(), TIMEOUT);
    if (!result.isSuccess()) {
      return false;
    }
    String assignment = key + "=" + value;
    return result
        .stdout()
        .lines()
        .flatMap(line -> java.util.Arrays.stream(line.strip().split("\\s+")))
        .anyMatch(assignment::equals);
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
