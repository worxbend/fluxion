package dev.sysboot.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.executor.state.record.BootstrapStateRecord;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Objects;
import java.util.Set;

final class SecureStateFileReader {

  private static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-------");

  private final ObjectMapper objectMapper;
  private final StatePermissionSetter permissionSetter;
  private final OwnerVerifier ownerVerifier;

  SecureStateFileReader(
      ObjectMapper objectMapper,
      StatePermissionSetter permissionSetter,
      OwnerVerifier ownerVerifier) {
    this.objectMapper = objectMapper;
    this.permissionSetter = permissionSetter;
    this.ownerVerifier = ownerVerifier;
  }

  BootstrapStateRecord read(Path stateFile) throws IOException {
    return read(stateFile, true);
  }

  BootstrapStateRecord readReadOnly(Path stateFile) throws IOException {
    return read(stateFile, false);
  }

  private BootstrapStateRecord read(Path stateFile, boolean repairPermissions) throws IOException {
    requireRegularFile(stateFile);
    ownerVerifier.verify(stateFile);
    requirePrivatePermissions(stateFile, repairPermissions);
    BasicFileAttributes before = attributes(stateFile);
    try (FileChannel channel =
            FileChannel.open(stateFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        InputStream input = Channels.newInputStream(channel)) {
      requireSameFile(before, attributes(stateFile), stateFile);
      return objectMapper.readValue(input, BootstrapStateRecord.class);
    }
  }

  private void requireRegularFile(Path stateFile) throws IOException {
    if (Files.isSymbolicLink(stateFile)
        || !Files.isRegularFile(stateFile, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("State file must be a regular file: " + stateFile);
    }
  }

  private void requirePrivatePermissions(Path stateFile, boolean repairPermissions)
      throws IOException {
    PosixFileAttributeView view =
        Files.getFileAttributeView(
            stateFile, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    if (view == null) {
      return;
    }
    Set<PosixFilePermission> current = view.readAttributes().permissions();
    if (current.contains(PosixFilePermission.GROUP_WRITE)
        || current.contains(PosixFilePermission.OTHERS_WRITE)) {
      throw new IOException("State file was writable by another account: " + stateFile);
    }
    if (repairPermissions && !current.equals(FILE_PERMISSIONS)) {
      permissionSetter.set(stateFile, FILE_PERMISSIONS);
    }
    if (!view.readAttributes().permissions().equals(FILE_PERMISSIONS)) {
      throw new IOException("State file permissions are not private: " + stateFile);
    }
  }

  private BasicFileAttributes attributes(Path path) throws IOException {
    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
  }

  private void requireSameFile(
      BasicFileAttributes before, BasicFileAttributes after, Path stateFile) throws IOException {
    if (!after.isRegularFile() || !sameIdentity(before, after)) {
      throw new IOException("State file changed while being opened: " + stateFile);
    }
  }

  private boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
    if (before.fileKey() != null || after.fileKey() != null) {
      return Objects.equals(before.fileKey(), after.fileKey());
    }
    return before.size() == after.size()
        && before.lastModifiedTime().equals(after.lastModifiedTime())
        && before.creationTime().equals(after.creationTime());
  }

  static void requireMatchingDirectoryOwner(Path stateFile) throws IOException {
    FileOwnerAttributeView fileView =
        Files.getFileAttributeView(
            stateFile, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
    FileOwnerAttributeView directoryView =
        Files.getFileAttributeView(
            PathRequirements.parent(stateFile, "State file"),
            FileOwnerAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
    if (fileView != null
        && directoryView != null
        && !fileView.getOwner().equals(directoryView.getOwner())) {
      throw new IOException("State file owner differs from state directory owner: " + stateFile);
    }
  }

  @FunctionalInterface
  interface OwnerVerifier {

    void verify(Path stateFile) throws IOException;
  }
}
