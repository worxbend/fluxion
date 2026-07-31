package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Path;

interface FileWriteFileSystem {

  Path createTempFile(String prefix, String suffix) throws IOException;

  default Path createTempFile(Path directory, String prefix, String suffix) throws IOException {
    return createTempFile(prefix, suffix);
  }

  void createDirectories(Path directory) throws IOException;

  void writeString(Path path, String content) throws IOException;

  void copy(Path source, Path destination) throws IOException;

  default void copyReadableRegularFile(Path source, Path destination) throws IOException {
    copy(source, destination);
  }

  void setMode(Path path, String mode) throws IOException;

  default void preserveMode(Path existing, Path staged) throws IOException {}

  void deleteIfExists(Path path) throws IOException;

  default void requireSafeDestination(Path destination, boolean privileged) throws IOException {}

  default void atomicReplace(Path source, Path destination) throws IOException {
    copy(source, destination);
    deleteIfExists(source);
  }
}
