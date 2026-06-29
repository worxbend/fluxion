package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Path;

interface FileWriteFileSystem {

  Path createTempFile(String prefix, String suffix) throws IOException;

  void createDirectories(Path directory) throws IOException;

  void writeString(Path path, String content) throws IOException;

  void copy(Path source, Path destination) throws IOException;

  void setMode(Path path, String mode) throws IOException;

  void deleteIfExists(Path path) throws IOException;
}
