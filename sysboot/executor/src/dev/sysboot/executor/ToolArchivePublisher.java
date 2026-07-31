package dev.sysboot.executor;

import dev.sysboot.core.ToolSpec;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

final class ToolArchivePublisher {

  static final long MAX_EXECUTABLE_BYTES = 32L * 1024 * 1024;
  private static final int MAX_DIAGNOSTIC_ENTRIES = 20;
  private static final int MAX_DIAGNOSTIC_NAME_LENGTH = 120;

  void publish(Path archive, ToolSpec spec, Path destination) throws IOException {
    Path staged =
        Files.createTempFile(
            PathRequirements.parent(destination, "Tool destination"),
            "." + destination.getFileName() + "-",
            ".part");
    try {
      Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rw-------"));
      extract(archive, spec, staged);
      Files.setPosixFilePermissions(staged, PosixFilePermissions.fromString("rwx------"));
      force(staged);
      Files.move(
          staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  private void extract(Path archive, ToolSpec spec, Path staged) throws IOException {
    String executable = spec.executableName();
    var seen = new ArrayList<String>();
    boolean found = false;
    try (var input = new BufferedInputStream(Files.newInputStream(archive));
        var gzip = new GzipCompressorInputStream(input);
        var tar = new TarArchiveInputStream(gzip)) {
      TarArchiveEntry entry;
      while ((entry = tar.getNextEntry()) != null) {
        if (matches(entry, executable)) {
          if (found) {
            throw duplicate(spec, executable);
          }
          requireRegularBoundedEntry(entry, spec);
          copyBounded(tar, staged, entry.getSize());
          found = true;
        }
        remember(entry, seen);
      }
    }
    if (!found) {
      throw missing(spec, executable, seen);
    }
  }

  private boolean matches(TarArchiveEntry entry, String executable) {
    if (entry.isDirectory()) {
      return false;
    }
    String name = entry.getName();
    return name.equals(executable)
        || name.endsWith("/" + executable)
        || name.equals("./" + executable);
  }

  private void requireRegularBoundedEntry(TarArchiveEntry entry, ToolSpec spec) {
    if (!entry.isFile() || entry.isSymbolicLink() || entry.isLink()) {
      throw new ToolResolutionException(
          "Archive for %s %s contains a non-regular executable entry"
              .formatted(spec.name(), spec.version()));
    }
    if (entry.getSize() < 0 || entry.getSize() > MAX_EXECUTABLE_BYTES) {
      throw new ToolResolutionException(
          "Archive executable for %s %s exceeds the %d-byte extraction limit"
              .formatted(spec.name(), spec.version(), MAX_EXECUTABLE_BYTES));
    }
  }

  private void copyBounded(InputStream input, Path destination, long declaredSize)
      throws IOException {
    long copied = 0;
    byte[] buffer = new byte[8192];
    try (OutputStream output =
        Files.newOutputStream(destination, StandardOpenOption.TRUNCATE_EXISTING)) {
      int read;
      while ((read = input.read(buffer)) != -1) {
        copied += read;
        if (copied > MAX_EXECUTABLE_BYTES) {
          throw new ToolResolutionException("Archive executable exceeds extraction limit");
        }
        output.write(buffer, 0, read);
      }
    }
    if (copied != declaredSize) {
      throw new ToolResolutionException("Archive executable size does not match its header");
    }
  }

  private void remember(TarArchiveEntry entry, ArrayList<String> seen) {
    if (entry.isDirectory() || seen.size() >= MAX_DIAGNOSTIC_ENTRIES) {
      return;
    }
    String name = entry.getName();
    seen.add(
        name.length() <= MAX_DIAGNOSTIC_NAME_LENGTH
            ? name
            : name.substring(0, MAX_DIAGNOSTIC_NAME_LENGTH) + "...");
  }

  private void force(Path staged) throws IOException {
    try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
      channel.force(true);
    }
  }

  private ToolResolutionException duplicate(ToolSpec spec, String executable) {
    return new ToolResolutionException(
        "Archive for %s %s contains more than one executable named '%s'"
            .formatted(spec.name(), spec.version(), executable));
  }

  private ToolResolutionException missing(
      ToolSpec spec, String executable, ArrayList<String> seen) {
    return new ToolResolutionException(
        "Archive for %s %s does not contain an executable named '%s'. It contains: %s"
            .formatted(spec.name(), spec.version(), executable, String.join(", ", seen)));
  }
}
