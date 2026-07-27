package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

/** systemd units to enable, start, stop, or mask. */
public record SystemdUnitModule(
    ModuleName name, SystemdScope scope, List<SystemdUnit> units, boolean continueOnError)
    implements BootstrapModule {

  public SystemdUnitModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(scope);
    units = List.copyOf(Objects.requireNonNull(units));
    if (units.isEmpty()) {
      throw new IllegalArgumentException("systemd-unit requires at least one unit");
    }
  }

  /**
   * @param unit unit name; a bare name is treated as a {@code .service}
   * @param enabled start at boot
   * @param state desired runtime state
   * @param masked link the unit to /dev/null so it cannot be started even as a dependency
   */
  public record SystemdUnit(String unit, boolean enabled, SystemdState state, boolean masked) {

    public SystemdUnit {
      Objects.requireNonNull(unit);
      Objects.requireNonNull(state);
      if (unit.isBlank()) {
        throw new IllegalArgumentException("systemd unit name must not be blank");
      }
      if (masked && enabled) {
        throw new IllegalArgumentException(
            "systemd unit '" + unit + "' cannot be both masked and enabled");
      }
    }

    /** systemd requires a suffix; assume a service, which is what almost every entry means. */
    public String qualifiedName() {
      return unit.contains(".") ? unit : unit + ".service";
    }
  }
}
