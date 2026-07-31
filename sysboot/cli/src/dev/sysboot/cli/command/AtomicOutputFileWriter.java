package dev.sysboot.cli.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Publishes generated CLI output without following a pre-positioned symbolic link. */
final class AtomicOutputFileWriter {

  private AtomicOutputFileWriter() {}

  static boolean exists(Path output) {
    return Files.exists(output, LinkOption.NOFOLLOW_LINKS);
  }

  static void write(Path output, String content, boolean replace) throws IOException {
    Path target = output.toAbsolutePath().normalize();
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("Output path has no parent");
    }
    createSafeDirectories(parent);
    Path staged = Files.createTempFile(parent, "." + target.getFileName() + "-", ".tmp");
    boolean published = false;
    try {
      Files.writeString(
          staged,
          content,
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE,
          LinkOption.NOFOLLOW_LINKS);
      requireNoSymlinkAncestry(parent);
      if (replace) {
        Files.move(
            staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } else {
        publishNew(staged, target);
      }
      published = true;
    } finally {
      if (!published) {
        Files.deleteIfExists(staged);
      }
    }
  }

  private static void publishNew(Path staged, Path target) throws IOException {
    if (exists(target)) {
      throw new FileAlreadyExistsException(target.toString());
    }
    Files.createLink(target, staged);
    Files.delete(staged);
  }

  private static void createSafeDirectories(Path directory) throws IOException {
    Path current = directory.getRoot();
    for (Path component : directory) {
      current = current.resolve(component);
      if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        requireDirectoryEntry(current);
      } else {
        Files.createDirectory(current);
      }
    }
    requireNoSymlinkAncestry(directory);
  }

  private static void requireNoSymlinkAncestry(Path directory) throws IOException {
    Path current = directory.getRoot();
    for (Path component : directory) {
      current = current.resolve(component);
      requireDirectoryEntry(current);
    }
  }

  private static void requireDirectoryEntry(Path path) throws IOException {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Output path must not traverse symbolic links: " + path);
    }
  }
}
