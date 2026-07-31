package dev.sysboot.executor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class DefaultPacmanRepositoryConfigFiles implements PacmanRepositoryConfigFiles {

  static final long MAX_CONFIG_BYTES = 4L * 1024L * 1024L;
  private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  @Override
  public String readTrusted(Path configPath) throws IOException {
    PosixFileAttributes attributes =
        Files.readAttributes(configPath, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    requireRegularBoundedFile(configPath, attributes);
    requireTrustedAncestry(PathRequirements.parent(configPath, "Pacman config"));
    requireTrustedOwnership(configPath, attributes);
    return readBounded(configPath);
  }

  @Override
  public Path stage(String content) throws IOException {
    if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONFIG_BYTES) {
      throw new IOException("Staged Pacman config exceeds maximum size");
    }
    Path staged =
        Files.createTempFile(
            "sysboot-pacman-", ".conf", PosixFilePermissions.asFileAttribute(PRIVATE_PERMISSIONS));
    boolean complete = false;
    try {
      Files.writeString(
          staged,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE);
      complete = true;
      return staged;
    } finally {
      if (!complete) {
        Files.deleteIfExists(staged);
      }
    }
  }

  @Override
  public void deleteIfExists(Path path) throws IOException {
    Files.deleteIfExists(path);
  }

  private void requireRegularBoundedFile(Path path, PosixFileAttributes attributes)
      throws IOException {
    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
      throw new IOException("Pacman config must be a regular non-symbolic file: " + path);
    }
    if (attributes.size() > MAX_CONFIG_BYTES) {
      throw new IOException("Pacman config exceeds maximum size: " + path);
    }
  }

  private void requireTrustedOwnership(Path path, PosixFileAttributes attributes)
      throws IOException {
    Set<PosixFilePermission> permissions = attributes.permissions();
    if (!"root".equals(attributes.owner().getName())
        || permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw new IOException(
          "Pacman config must be root-owned and not group/world-writable: " + path);
    }
  }

  private void requireTrustedAncestry(Path directory) throws IOException {
    if (directory == null) {
      throw new IOException("Pacman config has no parent directory");
    }
    Path absolute = directory.toAbsolutePath().normalize();
    Path current = absolute.getRoot();
    if (current == null) {
      throw new IOException("Pacman config ancestry has no filesystem root: " + directory);
    }
    requireTrustedDirectory(current);
    for (Path component : absolute) {
      current = current.resolve(component);
      requireTrustedDirectory(current);
    }
  }

  private void requireTrustedDirectory(Path path) throws IOException {
    PosixFileAttributes attributes =
        Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> permissions = attributes.permissions();
    if (!attributes.isDirectory()
        || attributes.isSymbolicLink()
        || !"root".equals(attributes.owner().getName())
        || permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw new IOException("Pacman config has unsafe privileged ancestry: " + path);
    }
  }

  private String readBounded(Path path) throws IOException {
    try (var input =
            Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        var output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > MAX_CONFIG_BYTES) {
          throw new IOException("Pacman config exceeds maximum size: " + path);
        }
        output.write(buffer, 0, read);
      }
      return output.toString(StandardCharsets.UTF_8);
    }
  }
}
