package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

final class TrustedSystemExecutable {

  private static final List<Path> SYSTEM_DIRECTORIES =
      List.of(Path.of("/usr/bin"), Path.of("/usr/sbin"), Path.of("/bin"), Path.of("/sbin"));
  private static final Set<PosixFilePermission> UNSAFE_WRITE_PERMISSIONS =
      Set.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

  private TrustedSystemExecutable() {}

  static Path gpg() {
    return GpgHolder.PATH;
  }

  static Path install() {
    return InstallHolder.PATH;
  }

  static Path compare() {
    return CompareHolder.PATH;
  }

  static Path move() {
    return MoveHolder.PATH;
  }

  static Path link() {
    return LinkHolder.PATH;
  }

  static Path remove() {
    return RemoveHolder.PATH;
  }

  static Path sha256sum() {
    return Sha256Holder.PATH;
  }

  static Path resolve(String executable) {
    Path requested = Path.of(executable);
    if (requested.isAbsolute()) {
      return requireTrusted(requested);
    }
    if (requested.getNameCount() != 1
        || executable.isBlank()
        || ".".equals(executable)
        || "..".equals(executable)) {
      throw new IllegalArgumentException(
          "Privileged executable must be a bare name or trusted absolute system path");
    }
    for (Path directory : SYSTEM_DIRECTORIES) {
      Path candidate = directory.resolve(executable);
      try {
        Path real = candidate.toRealPath();
        if (isTrustedExecutable(real)) {
          return real;
        }
      } catch (IOException ignored) {
        // Try the next fixed system location.
      }
    }
    throw new IllegalStateException(
        executable + " is not available from a trusted root-owned system directory");
  }

  private static Path requireTrusted(Path executable) {
    try {
      Path real = executable.toRealPath();
      if (isTrustedExecutable(real)) {
        return real;
      }
    } catch (IOException e) {
      throw new IllegalArgumentException(
          "Privileged executable is not an available trusted system executable: " + executable, e);
    }
    throw new IllegalArgumentException(
        "Privileged executable is not root-owned or is writable by an untrusted principal: "
            + executable);
  }

  private static boolean isTrustedExecutable(Path executable) throws IOException {
    if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
        || !Files.isExecutable(executable)
        || !isInSystemDirectory(executable)) {
      return false;
    }
    if (!hasTrustedAttributes(executable, false)) {
      return false;
    }
    Path ancestor = executable.getParent();
    while (ancestor != null) {
      if (!hasTrustedAttributes(ancestor, true)) {
        return false;
      }
      ancestor = ancestor.getParent();
    }
    return true;
  }

  private static boolean isInSystemDirectory(Path executable) {
    return SYSTEM_DIRECTORIES.stream().anyMatch(executable::startsWith);
  }

  private static boolean hasTrustedAttributes(Path path, boolean directory) throws IOException {
    PosixFileAttributes attributes =
        Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if ((directory && !attributes.isDirectory()) || (!directory && !attributes.isRegularFile())) {
      return false;
    }
    return attributes.owner().getName().equals("root")
        && attributes.permissions().stream().noneMatch(UNSAFE_WRITE_PERMISSIONS::contains);
  }

  private static final class GpgHolder {
    private static final Path PATH = resolve("gpg");
  }

  private static final class InstallHolder {
    private static final Path PATH = resolve("install");
  }

  private static final class CompareHolder {
    private static final Path PATH = resolve("cmp");
  }

  private static final class MoveHolder {
    private static final Path PATH = resolve("mv");
  }

  private static final class LinkHolder {
    private static final Path PATH = resolve("ln");
  }

  private static final class RemoveHolder {
    private static final Path PATH = resolve("rm");
  }

  private static final class Sha256Holder {
    private static final Path PATH = resolve("sha256sum");
  }
}
