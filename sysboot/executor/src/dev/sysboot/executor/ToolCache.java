package dev.sysboot.executor;

import dev.sysboot.core.ToolSpec;
import java.nio.file.Path;
import java.util.Objects;

/** Filesystem layout for tools Fluxion downloads on the user's behalf. */
final class ToolCache {

  private static final String CACHE_DIR = ".cache/fluxion/tools";

  private final Path baseDir;

  ToolCache() {
    this(Path.of(System.getProperty("user.home")).resolve(CACHE_DIR));
  }

  ToolCache(Path baseDir) {
    this.baseDir = Objects.requireNonNull(baseDir).toAbsolutePath().normalize();
  }

  Path baseDir() {
    return baseDir;
  }

  /** Directory holding one resolved version of one tool. */
  Path versionDir(ToolSpec spec) {
    return requireConfined(baseDir.resolve(spec.name()).resolve(spec.version()));
  }

  /** Path the executable is cached at once installed. */
  Path executable(ToolSpec spec) {
    return requireConfined(versionDir(spec).resolve(spec.executableName()));
  }

  Path installLock(ToolSpec spec) {
    Path executable = executable(spec);
    return requireConfined(
        executable.resolveSibling("." + executable.getFileName() + ".install.lock"));
  }

  Path integrityProof(ToolSpec spec) {
    Path executable = executable(spec);
    return requireConfined(executable.resolveSibling("." + executable.getFileName() + ".sha256"));
  }

  Path requireConfined(Path candidate) {
    Path normalized = Objects.requireNonNull(candidate).toAbsolutePath().normalize();
    if (normalized.equals(baseDir) || !normalized.startsWith(baseDir)) {
      throw new IllegalArgumentException("Tool cache path escapes the configured cache root");
    }
    return normalized;
  }
}
