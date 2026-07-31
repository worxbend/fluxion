package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

final class TrustedDestinationAncestry {

  private static final Set<PosixFilePermission> UNSAFE_WRITE_PERMISSIONS =
      Set.of(PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE);

  private TrustedDestinationAncestry() {}

  static void requireSafe(Path destination) throws IOException {
    requireSafe(destination, Path.of("/"), "root");
  }

  static void requireSafe(Path destination, Path trustedRoot, String owner) throws IOException {
    Path root = trustedRoot.toAbsolutePath().normalize();
    Path target = destination.toAbsolutePath().normalize();
    if (target.equals(root) || !target.startsWith(root)) {
      throw new IOException("Privileged destination escapes its trusted root");
    }
    Path current = root;
    for (Path component : root.relativize(target)) {
      current = current.resolve(component);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        return;
      }
      PosixFileAttributes attributes =
          Files.readAttributes(current, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      boolean destinationEntry = current.equals(target);
      if ((!destinationEntry && !attributes.isDirectory())
          || (destinationEntry && !attributes.isRegularFile())
          || !attributes.owner().getName().equals(owner)
          || attributes.permissions().stream().anyMatch(UNSAFE_WRITE_PERMISSIONS::contains)) {
        throw new IOException("Privileged destination has unsafe no-follow ancestry");
      }
    }
  }
}
