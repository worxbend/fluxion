package dev.sysboot.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class DefaultFileWriteFileSystem implements FileWriteFileSystem {

  @Override
  public Path createTempFile(String prefix, String suffix) throws IOException {
    return Files.createTempFile(prefix, suffix);
  }

  @Override
  public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
    return Files.createTempFile(directory, prefix, suffix);
  }

  @Override
  public void createDirectories(Path directory) throws IOException {
    Files.createDirectories(directory);
  }

  @Override
  public void writeString(Path path, String content) throws IOException {
    Files.writeString(
        path,
        content,
        StandardCharsets.UTF_8,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public void copy(Path source, Path destination) throws IOException {
    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public void copyReadableRegularFile(Path source, Path destination) throws IOException {
    if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("File write source must be a regular non-symbolic file: " + source);
    }
    try (var input =
            Files.newInputStream(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        var output =
            Files.newOutputStream(
                destination, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
      input.transferTo(output);
    }
  }

  @Override
  public void setMode(Path path, String mode) throws IOException {
    Set<PosixFilePermission> permissions =
        PosixFilePermissions.fromString(toPermissionString(mode));
    Files.setPosixFilePermissions(path, permissions);
  }

  @Override
  public void preserveMode(Path existing, Path staged) throws IOException {
    if (Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)
        && Files.getFileStore(existing).supportsFileAttributeView(PosixFileAttributeView.class)) {
      Files.setPosixFilePermissions(
          staged, Files.getPosixFilePermissions(existing, LinkOption.NOFOLLOW_LINKS));
    }
  }

  @Override
  public void deleteIfExists(Path path) throws IOException {
    Files.deleteIfExists(path);
  }

  @Override
  public void requireSafeDestination(Path destination, boolean privileged) throws IOException {
    Path normalized = destination.toAbsolutePath().normalize();
    Path current = normalized.getRoot();
    for (Path segment : normalized) {
      current = current.resolve(segment);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      if (Files.isSymbolicLink(current)) {
        throw new IOException(
            "File write destination must not traverse symbolic links: " + current);
      }
      if (!current.equals(normalized) && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("File write destination ancestor must be a directory: " + current);
      }
      if (privileged && !current.equals(normalized)) {
        requireTrustedPrivilegedAncestor(current);
      }
    }
    if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
        && !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("File write destination must be a regular file: " + normalized);
    }
  }

  @Override
  public void atomicReplace(Path source, Path destination) throws IOException {
    requireSafeDestination(destination, false);
    Files.move(
        source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  private void requireTrustedPrivilegedAncestor(Path directory) throws IOException {
    if (!Files.getFileStore(directory).supportsFileAttributeView("unix")) {
      return;
    }
    int uid = (int) Files.getAttribute(directory, "unix:uid", LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> permissions =
        Files.getPosixFilePermissions(directory, LinkOption.NOFOLLOW_LINKS);
    if (uid != 0
        || permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw new IOException(
          "Privileged file destination ancestor must be root-owned and non-writable: " + directory);
    }
  }

  private String toPermissionString(String mode) {
    int value = Integer.parseInt(mode, 8) & 0777;
    var permissions = new StringBuilder(9);
    appendTriplet(permissions, value >> 6);
    appendTriplet(permissions, value >> 3);
    appendTriplet(permissions, value);
    return permissions.toString();
  }

  private void appendTriplet(StringBuilder builder, int value) {
    builder.append((value & 4) != 0 ? 'r' : '-');
    builder.append((value & 2) != 0 ? 'w' : '-');
    builder.append((value & 1) != 0 ? 'x' : '-');
  }
}
