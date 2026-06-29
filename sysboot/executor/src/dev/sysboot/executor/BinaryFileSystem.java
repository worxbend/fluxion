package dev.sysboot.executor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

interface BinaryFileSystem {

  Path createTempFile(String prefix, String suffix) throws IOException;

  InputStream openInput(Path path) throws IOException;

  byte[] readAllBytes(Path path) throws IOException;

  void copy(Path source, Path destination) throws IOException;

  void copy(InputStream input, Path destination) throws IOException;

  void setMode(Path path, String mode) throws IOException;

  void createSymlink(Path link, Path target) throws IOException;

  boolean isRootOwned(Path path);

  void deleteIfExists(Path path) throws IOException;
}
