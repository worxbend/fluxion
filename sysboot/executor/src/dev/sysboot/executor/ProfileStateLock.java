package dev.sysboot.executor;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

final class ProfileStateLock {

  private static final Map<Path, ReentrantLock> PROCESS_LOCKS = new ConcurrentHashMap<>();
  private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final StatePaths statePaths;

  ProfileStateLock(StatePaths statePaths) {
    this.statePaths = statePaths;
  }

  <T> T withLock(String profileName, Supplier<T> operation) {
    return withLockFile(lockFilePath(profileName), profileName, operation);
  }

  <T> T withGlobalApplyLock(Supplier<T> operation) {
    return withLockFile(statePaths.baseDir().resolve(".apply.lock"), "global apply", operation);
  }

  private <T> T withLockFile(Path lockFile, String label, Supplier<T> operation) {
    ReentrantLock processLock =
        PROCESS_LOCKS.computeIfAbsent(lockFile, ignored -> new ReentrantLock());
    boolean nested = processLock.isHeldByCurrentThread();
    processLock.lock();
    try {
      if (nested) {
        return operation.get();
      }
      prepareDirectory(PathRequirements.parent(lockFile, "State lock file"));
      prepareLockFile(lockFile);
      return runLocked(lockFile, operation);
    } catch (IOException | SecurityException | UnsupportedOperationException e) {
      throw new StateWriteException("Failed to lock state for " + label, e);
    } finally {
      processLock.unlock();
    }
  }

  private <T> T runLocked(Path lockFile, Supplier<T> operation) throws IOException {
    try (FileChannel channel =
            FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        var stateLock = channel.lock()) {
      if (!stateLock.isValid()) {
        throw new IOException("State lock was not acquired: " + lockFile);
      }
      return operation.get();
    }
  }

  private Path lockFilePath(String profileName) {
    Path stateFile = statePaths.stateFile(profileName);
    return stateFile.resolveSibling("." + stateFile.getFileName() + ".lock");
  }

  private void prepareDirectory(Path directory) throws IOException {
    requireSafeRoot(directory);
    if (!exists(directory)) {
      Files.createDirectories(
          directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
    }
    requireSafeRoot(directory);
    setPrivatePermissions(directory, DIRECTORY_PERMISSIONS);
  }

  private void prepareLockFile(Path lockFile) throws IOException {
    try (FileChannel lockChannel =
        FileChannel.open(
            lockFile,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      lockChannel.force(false);
    }
    if (!Files.isRegularFile(lockFile, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(lockFile)) {
      throw new IOException("State lock must be a regular file: " + lockFile);
    }
    setPrivatePermissions(lockFile, FILE_PERMISSIONS);
  }

  private void requireSafeRoot(Path root) throws IOException {
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

  private void setPrivatePermissions(Path path, Set<PosixFilePermission> permissions)
      throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
      Files.setPosixFilePermissions(path, permissions);
    }
  }

  private boolean exists(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
  }
}
