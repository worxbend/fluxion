package dev.sysboot.executor;

import java.nio.file.Path;
import java.util.Objects;
import java.util.regex.Pattern;

public final class StatePaths {

  private static final String FILE_SUFFIX = ".state.json";
  private static final String STATE_DIR = ".local/share/fluxion";
  private static final String LEGACY_STATE_DIR = ".local/share/sysboot";
  private static final Pattern SAFE_SLUG =
      Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

  private final Path baseDir;
  private final Path legacyBaseDir;

  public StatePaths() {
    this(Path.of(System.getProperty("user.home")).resolve(STATE_DIR));
  }

  StatePaths(Path baseDir) {
    this(baseDir, Path.of(System.getProperty("user.home")).resolve(LEGACY_STATE_DIR));
  }

  StatePaths(Path baseDir, Path legacyBaseDir) {
    this.baseDir = normalizedRoot(baseDir);
    this.legacyBaseDir = normalizedRoot(legacyBaseDir);
  }

  public Path baseDir() {
    return baseDir;
  }

  public Path stateFile(String profileName) {
    return resolveStateFile(baseDir, profileName);
  }

  public Path legacyStateFile(String profileName) {
    return resolveStateFile(legacyBaseDir, profileName);
  }

  private Path resolveStateFile(Path root, String profileName) {
    requireSafeSlug(profileName);
    Path candidate = root.resolve(profileName + FILE_SUFFIX).normalize();
    if (!candidate.startsWith(root)) {
      throw new IllegalArgumentException("Profile state path escapes the configured state root");
    }
    return candidate;
  }

  private void requireSafeSlug(String profileName) {
    Objects.requireNonNull(profileName, "Profile name must not be null");
    if (!SAFE_SLUG.matcher(profileName).matches() || profileName.contains("..")) {
      throw new IllegalArgumentException(
          "Profile name must be a safe slug containing letters, numbers, dots, hyphens, or"
              + " underscores");
    }
  }

  private static Path normalizedRoot(Path root) {
    return Objects.requireNonNull(root, "State root must not be null").toAbsolutePath().normalize();
  }
}
