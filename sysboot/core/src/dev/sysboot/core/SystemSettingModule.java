package dev.sysboot.core;

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
}
