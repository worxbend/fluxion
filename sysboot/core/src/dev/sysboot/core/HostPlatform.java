package dev.sysboot.core;

import java.util.Locale;
import java.util.Objects;

/**
 * Operating system and CPU architecture of the machine being bootstrapped.
 *
 * <p>Release assets are named per platform, so tool resolution needs this rather than assuming
 * {@code linux-amd64}.
 */
public record HostPlatform(OperatingSystem operatingSystem, Architecture architecture) {

  public HostPlatform {
    Objects.requireNonNull(operatingSystem);
    Objects.requireNonNull(architecture);
  }

  public enum OperatingSystem {
    LINUX("linux", "linux"),
    MACOS("darwin", "macos");

    private final String goName;
    private final String alternateName;

    OperatingSystem(String goName, String alternateName) {
      this.goName = goName;
      this.alternateName = alternateName;
    }

    /** Name used by Go-style release assets, for example {@code linux} or {@code darwin}. */
    public String goName() {
      return goName;
    }

    /** Name used by release assets that spell macOS as {@code macos} rather than {@code darwin}. */
    public String alternateName() {
      return alternateName;
    }
  }

  public enum Architecture {
    AMD64("amd64", "x86_64"),
    ARM64("arm64", "aarch64");

    private final String goName;
    private final String unameName;

    Architecture(String goName, String unameName) {
      this.goName = goName;
      this.unameName = unameName;
    }

    public String goName() {
      return goName;
    }

    public String unameName() {
      return unameName;
    }
  }

  /** Detects the platform from JVM system properties. */
  public static HostPlatform detect() {
    return new HostPlatform(
        detectOperatingSystem(System.getProperty("os.name", "")),
        detectArchitecture(System.getProperty("os.arch", "")));
  }

  static OperatingSystem detectOperatingSystem(String osName) {
    String normalized = osName.toLowerCase(Locale.ROOT);
    if (normalized.contains("mac") || normalized.contains("darwin")) {
      return OperatingSystem.MACOS;
    }
    return OperatingSystem.LINUX;
  }

  static Architecture detectArchitecture(String osArch) {
    String normalized = osArch.toLowerCase(Locale.ROOT);
    if (normalized.contains("aarch64") || normalized.contains("arm64")) {
      return Architecture.ARM64;
    }
    return Architecture.AMD64;
  }

  @Override
  public String toString() {
    return operatingSystem.goName() + "/" + architecture.goName();
  }
}
