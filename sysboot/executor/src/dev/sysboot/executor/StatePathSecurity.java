package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class StatePathSecurity {

  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final StatePermissionSetter permissionSetter;

  StatePathSecurity(StatePermissionSetter permissionSetter) {
    this.permissionSetter = permissionSetter;
  }

  void prepareWriteDirectory(Path directory) throws IOException {
    requireSafeRoot(directory);
    FileAttribute<?>[] attributes = privateAttributes(directory, DIRECTORY_PERMISSIONS);
    Files.createDirectories(directory, attributes);
    requireSafeRoot(directory);
    permissionSetter.set(directory, DIRECTORY_PERMISSIONS);
  }

  void prepareReadDirectory(Path directory) throws IOException {
    inspectReadDirectory(directory, true);
  }

  void inspectReadDirectory(Path directory) throws IOException {
    inspectReadDirectory(directory, false);
  }

  private void inspectReadDirectory(Path directory, boolean repairPermissions) throws IOException {
    requireSafeRoot(directory);
    if (!exists(directory)) {
      return;
    }
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("State root must be a directory: " + directory);
    }
    requirePrivatePermissions(directory, DIRECTORY_PERMISSIONS, repairPermissions);
  }

  void prepareExistingFile(Path stateFile) throws IOException {
    requireSafeRoot(PathRequirements.parent(stateFile, "State file"));
    rejectEscapingFile(stateFile);
  }

  void prepareDirectoryForDelete(Path directory) throws IOException {
    requireSafeRoot(directory);
    if (!exists(directory)) {
      return;
    }
    try {
      permissionSetter.set(directory, DIRECTORY_PERMISSIONS);
    } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
      // Deleting known state is safer than retaining it when chmod is unavailable.
    }
  }

  Path createPrivateTempFile(Path stateFile) throws IOException {
    String prefix = "." + stateFile.getFileName() + "-";
    Path parent = PathRequirements.parent(stateFile, "State file");
    FileAttribute<?>[] attributes = privateAttributes(parent, FILE_PERMISSIONS);
    return Files.createTempFile(parent, prefix, ".tmp", attributes);
  }

  void enforcePrivateFile(Path path) throws IOException {
    permissionSetter.set(path, FILE_PERMISSIONS);
  }

  void requireSafeRoot(Path root) throws IOException {
    Path current = root.getRoot();
    for (Path segment : root) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw new IOException("State root must not contain symbolic links: " + root);
      }
      if (!exists(current)) {
        return;
      }
    }
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("State root must be a directory: " + root);
    }
  }

  private void rejectEscapingFile(Path stateFile) throws IOException {
    if (!exists(stateFile)) {
      return;
    }
    if (Files.isSymbolicLink(stateFile)) {
      throw new IOException("State file must not be a symbolic link: " + stateFile);
    }
    Path realRoot =
        PathRequirements.parent(stateFile, "State file").toRealPath(LinkOption.NOFOLLOW_LINKS);
    if (!stateFile.toRealPath().startsWith(realRoot)) {
      throw new IOException("State file escapes the configured state root: " + stateFile);
    }
  }

  private void requirePrivatePermissions(
      Path path, Set<PosixFilePermission> permissions, boolean repairPermissions)
      throws IOException {
    if (!supportsPosix(path)) {
      return;
    }
    Set<PosixFilePermission> current =
        Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
    if (isWritableByOthers(current)) {
      throw new IOException("State path was writable by another account: " + path);
    }
    if (repairPermissions && !current.equals(permissions)) {
      permissionSetter.set(path, permissions);
    }
    if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(permissions)) {
      throw new IOException("State path permissions are not private: " + path);
    }
  }

  private boolean isWritableByOthers(Set<PosixFilePermission> permissions) {
    return permissions.contains(PosixFilePermission.GROUP_WRITE)
        || permissions.contains(PosixFilePermission.OTHERS_WRITE);
  }

  private FileAttribute<?>[] privateAttributes(Path path, Set<PosixFilePermission> permissions)
      throws IOException {
    if (!supportsPosix(path)) {
      return new FileAttribute<?>[0];
    }
    return new FileAttribute<?>[] {PosixFilePermissions.asFileAttribute(permissions)};
  }

  static void setPrivatePermissions(Path path, Set<PosixFilePermission> permissions)
      throws IOException {
    if (supportsPosix(path)) {
      Files.setPosixFilePermissions(path, permissions);
    }
  }

  private static boolean supportsPosix(Path path) throws IOException {
    Path existing = path;
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }
    return existing != null
        && Files.getFileStore(existing).supportsFileAttributeView(PosixFileAttributeView.class);
  }

  private boolean exists(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
  }
}
