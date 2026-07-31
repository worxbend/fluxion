package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public final class RepositoryDestinationPolicy {

  private static final Path APT_SOURCES = Path.of("/etc/apt/sources.list.d");
  private static final Path APT_KEYRINGS = Path.of("/etc/apt/keyrings");
  private static final Path SHARED_KEYRINGS = Path.of("/usr/share/keyrings");
  private static final Path RPM_KEYS = Path.of("/etc/pki/rpm-gpg");
  private static final Path RPM_REPOSITORIES = Path.of("/etc/yum.repos.d");
  private static final Path ZYPPER_KEYS = Path.of("/etc/zypp/keys");
  private static final Path ZYPPER_REPOSITORIES = Path.of("/etc/zypp/repos.d");
  private static final Path PACMAN_CONFIG = Path.of("/etc/pacman.conf");
  private static final Path PACMAN_INCLUDES = Path.of("/etc/pacman.d");
  private static final Set<String> APT_KEY_EXTENSIONS = Set.of(".gpg", ".asc");
  private static final Set<String> SYSTEM_KEY_EXTENSIONS = Set.of(".gpg", ".asc", ".key");

  private RepositoryDestinationPolicy() {}

  public static Path requireAptSourceList(Path path) {
    return requireDirectChild(path, APT_SOURCES, Set.of(".list"), "APT source-list path");
  }

  public static Path requireAptKeyring(Path path) {
    Path normalized = requireNormalizedAbsolute(path, "APT keyring path");
    if (!isDirectChild(normalized, APT_KEYRINGS) && !isDirectChild(normalized, SHARED_KEYRINGS)) {
      throw new IllegalArgumentException(
          "APT keyring path must be directly under /etc/apt/keyrings or /usr/share/keyrings");
    }
    return requireExtension(normalized, APT_KEY_EXTENSIONS, "APT keyring path");
  }

  public static Path requireGpgKeyring(Path path) {
    Path normalized = requireNormalizedAbsolute(path, "GPG keyring path");
    if (isDirectChild(normalized, APT_KEYRINGS) || isDirectChild(normalized, SHARED_KEYRINGS)) {
      return requireExtension(normalized, APT_KEY_EXTENSIONS, "GPG keyring path");
    }
    if (isDirectChild(normalized, RPM_KEYS)) {
      String fileName = requireFileName(normalized, "GPG keyring path");
      if (fileName.startsWith("RPM-GPG-KEY-")) {
        return normalized;
      }
      return requireExtension(normalized, SYSTEM_KEY_EXTENSIONS, "GPG keyring path");
    }
    if (isDirectChild(normalized, ZYPPER_KEYS)) {
      return requireExtension(normalized, SYSTEM_KEY_EXTENSIONS, "GPG keyring path");
    }
    throw new IllegalArgumentException(
        "GPG keyring path must be directly under an approved system key directory");
  }

  public static Path requireRpmRepository(Path path) {
    return requireDirectChild(path, RPM_REPOSITORIES, Set.of(".repo"), "RPM repository-file path");
  }

  public static Path requireZypperRepository(Path path) {
    return requireDirectChild(
        path, ZYPPER_REPOSITORIES, Set.of(".repo"), "Zypper repository-file path");
  }

  public static Path requirePacmanConfig(Path path) {
    Path normalized = requireNormalizedAbsolute(path, "Pacman config path");
    if (!PACMAN_CONFIG.equals(normalized)) {
      throw new IllegalArgumentException("Pacman config path must be /etc/pacman.conf");
    }
    return normalized;
  }

  public static Path requirePacmanInclude(Path path) {
    return requireDirectChild(path, PACMAN_INCLUDES, Set.of(), "Pacman include path");
  }

  private static Path requireDirectChild(
      Path path, Path root, Set<String> extensions, String subject) {
    Path normalized = requireNormalizedAbsolute(path, subject);
    if (!isDirectChild(normalized, root)) {
      throw new IllegalArgumentException(subject + " must be directly under " + root);
    }
    return requireExtension(normalized, extensions, subject);
  }

  private static Path requireNormalizedAbsolute(Path path, String subject) {
    Objects.requireNonNull(path, subject + " must not be null");
    if (!path.isAbsolute() || containsControl(path.toString())) {
      throw new IllegalArgumentException(subject + " must be an absolute single-line path");
    }
    return path.normalize();
  }

  private static Path requireExtension(Path path, Set<String> extensions, String subject) {
    if (extensions.isEmpty()) {
      return path;
    }
    String fileName = requireFileName(path, subject);
    if (extensions.stream().noneMatch(fileName::endsWith)) {
      throw new IllegalArgumentException(
          subject + " must use one of these extensions: " + String.join(", ", extensions));
    }
    return path;
  }

  private static boolean isDirectChild(Path path, Path root) {
    return root.equals(path.getParent());
  }

  private static String requireFileName(Path path, String subject) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalArgumentException(subject + " must identify a file");
    }
    return fileName.toString();
  }

  private static boolean containsControl(String value) {
    return value.codePoints().anyMatch(Character::isISOControl);
  }
}
