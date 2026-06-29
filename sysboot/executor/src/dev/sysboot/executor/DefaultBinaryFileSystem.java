package dev.sysboot.executor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class DefaultBinaryFileSystem implements BinaryFileSystem {

  @Override
  public Path createTempFile(String prefix, String suffix) throws IOException {
    return Files.createTempFile(prefix, suffix);
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
  public void copy(InputStream input, Path destination) throws IOException {
    Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public void setMode(Path path, String mode) throws IOException {
    Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(toPermissionString(mode));
    Files.setPosixFilePermissions(path, permissions);
  }

  @Override
  public void createSymlink(Path link, Path target) throws IOException {
    Files.deleteIfExists(link);
    Files.createSymbolicLink(link, target);
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
  public void deleteIfExists(Path path) throws IOException {
    Files.deleteIfExists(path);
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
