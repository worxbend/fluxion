package dev.sysboot.executor;

import java.nio.file.Path;
import java.util.Objects;

public final class StatePaths {

  private static final String FILE_SUFFIX = ".state.json";
  private static final String STATE_DIR = ".local/share/fluxion";
  private static final String LEGACY_STATE_DIR = ".local/share/sysboot";

  private final Path baseDir;
  private final Path legacyBaseDir;

  public StatePaths() {
    this(Path.of(System.getProperty("user.home")).resolve(STATE_DIR));
  }

  StatePaths(Path baseDir) {
    this(baseDir, Path.of(System.getProperty("user.home")).resolve(LEGACY_STATE_DIR));
  }

  StatePaths(Path baseDir, Path legacyBaseDir) {
    this.baseDir = Objects.requireNonNull(baseDir);
    this.legacyBaseDir = Objects.requireNonNull(legacyBaseDir);
  }

  public Path baseDir() {
    return baseDir;
  }

  public Path stateFile(String profileName) {
    return baseDir.resolve(profileName + FILE_SUFFIX);
  }

  public Path legacyStateFile(String profileName) {
    return legacyBaseDir.resolve(profileName + FILE_SUFFIX);
  }
}
