package dev.sysboot.executor;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedList;
import java.util.Set;

final class DefaultBinaryFileSystem implements BinaryFileSystem {

  private static final int COPY_BUFFER_BYTES = 64 * 1024;

  @Override
  public Path createTempFile(String prefix, String suffix) throws IOException {
    return Files.createTempFile(prefix, suffix);
  }

  @Override
  public Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
    return Files.createTempFile(directory, prefix, suffix);
  }

  @Override
  public Path createTempDirectory(Path directory, String prefix) throws IOException {
    return Files.createTempDirectory(directory, prefix);
  }

  @Override
  public void createDirectories(Path directory) throws IOException {
    Files.createDirectories(directory);
  }

  @Override
  public InputStream openInput(Path path) throws IOException {
    return Files.newInputStream(path);
  }

  @Override
  public byte[] readAllBytes(Path path) throws IOException {
    return Files.readAllBytes(path);
  }

  @Override
  public void copy(Path source, Path destination) throws IOException {
    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public void copyWithAttributes(Path source, Path destination) throws IOException {
    Files.copy(
        source,
        destination,
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES);
  }

  @Override
  public Sha256Digest copyAndDigest(InputStream input, Path destination, long maxBytes)
      throws IOException {
    try (OutputStream output =
        Files.newOutputStream(
            destination,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      return copyBounded(input, output, maxBytes);
    }
  }

  @Override
  public void setMode(Path path, String mode) throws IOException {
    Set<PosixFilePermission> permissions =
        PosixFilePermissions.fromString(toPermissionString(mode));
    Files.setPosixFilePermissions(path, permissions);
  }

  @Override
  public void createSymlink(Path link, Path target) throws IOException {
    Path staged =
        Files.createTempFile(
            PathRequirements.parent(link, "Symlink path"), ".sysboot-link-", ".tmp");
    boolean installed = false;
    try {
      Files.delete(staged);
      Files.createSymbolicLink(staged, target);
      atomicMoveReplace(staged, link);
      installed = true;
    } finally {
      if (!installed) {
        Files.deleteIfExists(staged);
      }
    }
  }

  @Override
  public void createHardLink(Path link, Path existing) throws IOException {
    Files.createLink(link, existing);
  }

  @Override
  public void atomicMoveReplace(Path source, Path destination) throws IOException {
    Files.move(
        source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public boolean exists(Path path) {
    return Files.exists(path);
  }

  @Override
  public boolean pathEntryExists(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public boolean isSymbolicLink(Path path) {
    return Files.isSymbolicLink(path);
  }

  @Override
  public boolean isRegularFile(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
  }

  @Override
  public Path readSymbolicLink(Path path) throws IOException {
    return Files.readSymbolicLink(path);
  }

  @Override
  public String fileIdentity(Path path) throws IOException {
    BasicFileAttributes attributes =
        Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    return attributes.fileKey()
        + ":"
        + attributes.size()
        + ":"
        + attributes.lastModifiedTime().toMillis();
  }

  @Override
  public Path resolvePhysicalEntry(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    var unresolved = new LinkedList<Path>();
    Path name = absolute.getFileName();
    Path current = absolute.getParent();
    if (name == null || current == null) {
      throw new IOException("Cannot resolve path ancestry: " + path);
    }
    unresolved.add(name);
    while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
      name = current.getFileName();
      Path parent = current.getParent();
      if (name == null || parent == null) {
        throw new IOException("Cannot resolve path ancestry: " + path);
      }
      unresolved.addFirst(name);
      current = parent;
    }
    Path resolved = current.toRealPath();
    for (Path component : unresolved) {
      resolved = resolved.resolve(component);
    }
    return resolved.normalize();
  }

  @Override
  public void requireNoSymlinkAncestors(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path parent = absolute.getParent();
    Path current = absolute.getRoot();
    if (parent == null || current == null) {
      throw new IOException("Cannot inspect path ancestry: " + path);
    }
    for (Path component : parent) {
      current = current.resolve(component);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Refusing symlinked or non-directory path ancestor: " + current);
      }
    }
  }

  @Override
  public boolean isWritable(Path path) {
    return Files.isDirectory(path) && Files.isWritable(path);
  }

  @Override
  public boolean isRootOwned(Path path) {
    try {
      return "root".equals(Files.getOwner(path).getName());
    } catch (IOException e) {
      return false;
    }
  }

  @Override
  public boolean isSecurePrivilegedDirectory(Path path) {
    try {
      Path absolute = path.toAbsolutePath().normalize();
      Path current = absolute.getRoot();
      if (current == null) {
        return false;
      }
      if (!isSecurePrivilegedComponent(current)) {
        return false;
      }
      for (Path component : absolute) {
        current = current.resolve(component);
        if (!isSecurePrivilegedComponent(current)) {
          return false;
        }
      }
      return true;
    } catch (IOException | UnsupportedOperationException e) {
      return false;
    }
  }

  @Override
  public void deleteIfExists(Path path) throws IOException {
    Files.deleteIfExists(path);
  }

  @Override
  public void deleteTreeIfExists(Path path) throws IOException {
    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      Files.deleteIfExists(path);
      return;
    }
    Files.walkFileTree(
        path,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException failure)
              throws IOException {
            if (failure != null) {
              throw failure;
            }
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private boolean isSecurePrivilegedComponent(Path path) throws IOException {
    PosixFileAttributes attributes =
        Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    Set<PosixFilePermission> permissions = attributes.permissions();
    return attributes.isDirectory()
        && !attributes.isSymbolicLink()
        && "root".equals(attributes.owner().getName())
        && !permissions.contains(PosixFilePermission.GROUP_WRITE)
        && !permissions.contains(PosixFilePermission.OTHERS_WRITE);
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

  private Sha256Digest copyBounded(InputStream input, OutputStream output, long maxBytes)
      throws IOException {
    MessageDigest digest = sha256();
    byte[] buffer = new byte[COPY_BUFFER_BYTES];
    long copied = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (read == 0) {
        continue;
      }
      copied += read;
      if (copied > maxBytes) {
        throw new IOException("Extracted entry exceeds maximum size of " + maxBytes + " bytes");
      }
      output.write(buffer, 0, read);
      digest.update(buffer, 0, read);
    }
    return new Sha256Digest(HexFormat.of().formatHex(digest.digest()));
  }

  private MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
