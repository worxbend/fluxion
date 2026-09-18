package dev.sysboot.core;

import java.util.Locale;

/**
 * Canonical CPU architecture spelling, shared by host detection and {@code when:} condition
 * matching so a profile written as {@code x86_64} names the same architecture a host reports as
 * {@code amd64}.
 */
public final class ArchitectureAliases {

  private ArchitectureAliases() {}

  public static String canonicalize(String value) {
    String normalized = value.strip().toLowerCase(Locale.ROOT);
    if (normalized.isBlank()) {
      return normalized;
    }
    return switch (normalized) {
      case "x86_64", "amd64" -> "amd64";
      case "aarch64", "arm64" -> "arm64";
      case "armv7l", "armv7", "armhf" -> "armv7";
      case "i386", "i486", "i586", "i686", "x86" -> "386";
      default -> normalized;
    };
  }
}
