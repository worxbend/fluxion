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
    this.baseDir = Objects.requireNonNull(baseDir);
  }

  Path baseDir() {
    return baseDir;
  }

  /** Directory holding one resolved version of one tool. */
  Path versionDir(ToolSpec spec) {
    return baseDir.resolve(spec.name()).resolve(spec.version());
  }

  /** Path the executable is cached at once installed. */
  Path executable(ToolSpec spec) {
    return versionDir(spec).resolve(spec.executableName());
  }
}
