package dev.sysboot.executor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

final class PathRequirements {

  private PathRequirements() {}

  static Path parent(Path path, String subject) throws IOException {
    Objects.requireNonNull(path, subject + " must not be null");
    Path parent = path.getParent();
    if (parent == null) {
      throw new IOException(subject + " must have a parent directory: " + path);
    }
    return parent;
  }
}
