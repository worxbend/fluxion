package dev.sysboot.executor;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

interface BinaryFileSystem {

  Path createTempFile(String prefix, String suffix) throws IOException;

  Path createTempFile(Path directory, String prefix, String suffix) throws IOException;

  Path createTempDirectory(Path directory, String prefix) throws IOException;

  void createDirectories(Path directory) throws IOException;

  InputStream openInput(Path path) throws IOException;

  byte[] readAllBytes(Path path) throws IOException;

  void copy(Path source, Path destination) throws IOException;

  void copyWithAttributes(Path source, Path destination) throws IOException;

  Sha256Digest copyAndDigest(InputStream input, Path destination, long maxBytes) throws IOException;

  void setMode(Path path, String mode) throws IOException;

  void createSymlink(Path link, Path target) throws IOException;

  void createHardLink(Path link, Path existing) throws IOException;

  void atomicMoveReplace(Path source, Path destination) throws IOException;

  boolean exists(Path path);

  boolean pathEntryExists(Path path);

  boolean isSymbolicLink(Path path);

  boolean isRegularFile(Path path);

  Path readSymbolicLink(Path path) throws IOException;

  String fileIdentity(Path path) throws IOException;

  Path resolvePhysicalEntry(Path path) throws IOException;

  void requireNoSymlinkAncestors(Path path) throws IOException;

  boolean isWritable(Path path);

  boolean isRootOwned(Path path);

  boolean isSecurePrivilegedDirectory(Path path);

  void deleteIfExists(Path path) throws IOException;

  void deleteTreeIfExists(Path path) throws IOException;
}
