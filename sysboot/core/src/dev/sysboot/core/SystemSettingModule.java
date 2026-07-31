package dev.sysboot.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Host-level settings managed by the {@code systemd} control tools.
 *
 * <p>Covers the {@code timedatectl} / {@code hostnamectl} / {@code localectl} family. Each is
 * probed with the matching {@code show} command so the step is idempotent and drift shows up in
 * {@code fluxion diff} rather than being reapplied blindly every run.
 */
public record SystemSettingModule(
    ModuleName name,
    Optional<Boolean> localRtc,
    Optional<Boolean> ntp,
    Optional<String> timezone,
    Optional<String> hostname,
    Map<String, String> locale,
    boolean continueOnError)
    implements BootstrapModule {

  public SystemSettingModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(localRtc);
    Objects.requireNonNull(ntp);
    Objects.requireNonNull(timezone);
    Objects.requireNonNull(hostname);
    locale = Map.copyOf(Objects.requireNonNull(locale));
    if (localRtc.isEmpty()
        && ntp.isEmpty()
        && timezone.isEmpty()
        && hostname.isEmpty()
        && locale.isEmpty()) {
      throw new IllegalArgumentException("system-setting requires at least one setting");
    }
  }

  /** Canonical item keys in deterministic execution and plan order. */
  public List<String> itemKeys() {
    var keys = new java.util.ArrayList<String>();
    localRtc.ifPresent(ignored -> keys.add("localRtc"));
    ntp.ifPresent(ignored -> keys.add("ntp"));
    timezone.ifPresent(ignored -> keys.add("timezone"));
    hostname.ifPresent(ignored -> keys.add("hostname"));
    locale.keySet().stream().sorted().map(key -> "locale:" + key).forEach(keys::add);
    return List.copyOf(keys);
  }
}
