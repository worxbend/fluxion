package dev.sysboot.executor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

final class DefaultFileWriteFileSystem implements FileWriteFileSystem {

  @Override
  public Path createTempFile(String prefix, String suffix) throws IOException {
    return Files.createTempFile(prefix, suffix);
  }

  @Override
  public void createDirectories(Path directory) throws IOException {
    Files.createDirectories(directory);
  }

  @Override
  public void writeString(Path path, String content) throws IOException {
    Files.writeString(path, content, StandardCharsets.UTF_8);
  }

  @Override
  public void copy(Path source, Path destination) throws IOException {
    Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
  }

  @Override
  public void setMode(Path path, String mode) throws IOException {
    Set<PosixFilePermission> permissions = PosixFilePermissions.fromString(toPermissionString(mode));
    Files.setPosixFilePermissions(path, permissions);
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
