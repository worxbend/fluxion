package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemdUnitModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Enables, starts, stops and masks systemd units.
 *
 * <p>{@code systemctl is-enabled} distinguishes several states through its exit code and its stdout
 * word, and reading them loosely is what makes a step either re-run forever or skip work it should
 * have done. The word is what matters: {@code static}, {@code masked}, {@code indirect} and {@code
 * generated} all mean "enabling this is not the thing to do", while only {@code enabled} and {@code
 * enabled-runtime} mean it is already on.
 */
public final class SystemdUnitExecutor {

  private static final Duration TIMEOUT = Duration.ofMinutes(2);
  private static final List<String> ALREADY_ENABLED = List.of("enabled", "enabled-runtime");
  private static final List<String> CANNOT_ENABLE =
      List.of("static", "indirect", "generated", "transient", "alias");

  private final ShellRunner shellRunner;

  public SystemdUnitExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(SystemdUnitModule module) {
    if (!systemctlAvailable()) {
      // Containers and image builds frequently have no running systemd. Failing there would make
      // an otherwise portable profile unusable in CI, so skip rather than fail.
      return new StepResult.Skipped(module.name().value(), "systemctl is not available");
    }
    var failures = new ArrayList<String>();
    for (SystemdUnitModule.SystemdUnit unit : module.units()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      apply(module, unit).ifPresent(failures::add);
    }
    return StepOutcome.of(module.name(), failures, module.continueOnError());
  }

  /** True when the unit is already in the requested enablement and runtime state. */
  public boolean alreadySatisfied(SystemdUnitModule module, SystemdUnitModule.SystemdUnit unit) {
    if (unit.masked()) {
      return "masked".equals(enablementOf(module, unit).orElse(""));
    }
    boolean enablementOk = !unit.enabled() || isEnabled(module, unit);
    return enablementOk && runtimeSatisfied(module, unit);
  }

  public List<String> commandPreview(SystemdUnitModule module) {
    var preview = new ArrayList<String>();
    for (SystemdUnitModule.SystemdUnit unit : module.units()) {
      if (unit.masked()) {
        preview.addAll(systemctl(module, "mask", unit.qualifiedName()));
        continue;
      }
      if (unit.enabled()) {
        preview.addAll(systemctl(module, "enable", unit.qualifiedName()));
      }
      switch (unit.state()) {
        case STARTED -> preview.addAll(systemctl(module, "start", unit.qualifiedName()));
        case STOPPED -> preview.addAll(systemctl(module, "stop", unit.qualifiedName()));
        case UNCHANGED -> {}
      }
    }
    return List.copyOf(preview);
  }

  private Optional<String> apply(SystemdUnitModule module, SystemdUnitModule.SystemdUnit unit) {
    if (unit.masked()) {
      return runStep(module, "mask", unit);
    }
    if (unit.enabled() && !isEnabled(module, unit)) {
      String enablement = enablementOf(module, unit).orElse("");
      if (CANNOT_ENABLE.contains(enablement)) {
        // A static unit has no [Install] section; `systemctl enable` on it is an error, and the
        // unit is already reachable as a dependency, so there is nothing to do.
        return Optional.empty();
      }
      Optional<String> failure = runStep(module, "enable", unit);
      if (failure.isPresent()) {
        return failure;
      }
    }
    return switch (unit.state()) {
      case STARTED -> isActive(module, unit) ? Optional.empty() : runStep(module, "start", unit);
      case STOPPED -> isActive(module, unit) ? runStep(module, "stop", unit) : Optional.empty();
      case UNCHANGED -> Optional.empty();
    };
  }

  private Optional<String> runStep(
      SystemdUnitModule module, String verb, SystemdUnitModule.SystemdUnit unit) {
    ProcessResult result = run(systemctl(module, verb, unit.qualifiedName()));
    return result.isSuccess()
        ? Optional.empty()
        : Optional.of(verb + " " + unit.qualifiedName() + ": " + StepOutcome.detail(result));
  }

  private boolean runtimeSatisfied(SystemdUnitModule module, SystemdUnitModule.SystemdUnit unit) {
    return switch (unit.state()) {
      case STARTED -> isActive(module, unit);
      case STOPPED -> !isActive(module, unit);
      case UNCHANGED -> true;
    };
  }

  private boolean isEnabled(SystemdUnitModule module, SystemdUnitModule.SystemdUnit unit) {
    return ALREADY_ENABLED.contains(enablementOf(module, unit).orElse(""));
  }

  private Optional<String> enablementOf(
      SystemdUnitModule module, SystemdUnitModule.SystemdUnit unit) {
    // is-enabled exits non-zero for disabled/static/masked but still prints the word, so the exit
    // code alone is not enough to tell those apart.
    ProcessResult result = run(systemctl(module, "is-enabled", unit.qualifiedName()));
    String word = result.stdout().strip().lines().findFirst().orElse("");
    return word.isBlank() ? Optional.empty() : Optional.of(word);
  }

  private boolean isActive(SystemdUnitModule module, SystemdUnitModule.SystemdUnit unit) {
    return "active"
        .equals(
            run(systemctl(module, "is-active", unit.qualifiedName()))
                .stdout()
                .strip()
                .lines()
                .findFirst()
                .orElse(""));
  }

  private boolean systemctlAvailable() {
    return run(List.of("systemctl", "--version")).isSuccess();
  }

  private List<String> systemctl(SystemdUnitModule module, String verb, String unit) {
    var command = new ArrayList<String>();
    if (module.scope().privileged() && !verb.startsWith("is-")) {
      command.add("sudo");
    }
    command.addAll(List.of("systemctl", module.scope().flag(), verb, unit));
    return List.copyOf(command);
  }

  private ProcessResult run(List<String> command) {
    return shellRunner.run(command, Map.of(), TIMEOUT);
  }
}
