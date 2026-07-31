package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

final class CompiledBinaryConstraints {

  private static final Set<String> ARCHIVE_SUFFIXES = Set.of(".tar.gz", ".tgz", ".tar.xz", ".zip");

  private CompiledBinaryConstraints() {}

  static void requireFileName(String value) {
    if (value.isBlank()) {
      throw new IllegalArgumentException("Binary name must not be blank");
    }
    if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")) {
      throw new IllegalArgumentException("Binary name must be a file name, not a path");
    }
  }

  static void requireAbsoluteNormalized(Path path, String subject) {
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException(subject + " must be absolute");
    }
    if (!path.equals(path.normalize())) {
      throw new IllegalArgumentException(subject + " must be normalized");
    }
  }

  static String requireArchivePath(String value) {
    if (value.isBlank()) {
      throw new IllegalArgumentException("Archive path must not be blank");
    }
    Path path = Path.of(value);
    if (path.isAbsolute()
        || path.startsWith("..")
        || !path.equals(path.normalize())
        || !value.equals(path.toString())
        || value.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("Archive path must be a normalized relative POSIX path");
    }
    return value;
  }

  static boolean isArchive(BinaryUrl url) {
    String path = url.value().getPath().toLowerCase(Locale.ROOT);
    return ARCHIVE_SUFFIXES.stream().anyMatch(path::endsWith);
  }

  static String normalizeSignerFingerprint(String fingerprint) {
    String normalized = fingerprint.strip().toUpperCase(Locale.ROOT);
    if (!normalized.matches("(?:[0-9A-F]{40}|[0-9A-F]{64})")) {
      throw new IllegalArgumentException(
          "Allowed signer fingerprint must contain exactly 40 or 64 hexadecimal characters");
    }
    return normalized;
  }
}
