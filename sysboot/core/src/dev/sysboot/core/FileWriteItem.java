package dev.sysboot.core;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record FileWriteItem(
    String name,
    Path destination,
    Optional<String> content,
    Optional<Path> source,
    Optional<String> owner,
    Optional<String> group,
    Optional<String> mode,
    boolean sudo) {

  public FileWriteItem {
    Objects.requireNonNull(name);
    Objects.requireNonNull(destination);
    Objects.requireNonNull(content);
    Objects.requireNonNull(source);
    Objects.requireNonNull(owner);
    Objects.requireNonNull(group);
    Objects.requireNonNull(mode);
    if (name.isBlank()) {
      throw new IllegalArgumentException("File write name must not be blank");
    }
    if (!destination.isAbsolute()) {
      throw new IllegalArgumentException("File write destination must be absolute");
    }
    if (content.isPresent() == source.isPresent()) {
      throw new IllegalArgumentException("File write must define exactly one of content or source");
    }
    source.ifPresent(FileWriteItem::requireAbsoluteSource);
    owner.ifPresent(value -> requirePresent(value, "File write owner"));
    group.ifPresent(value -> requirePresent(value, "File write group"));
    mode.ifPresent(FileWriteItem::requireOctalMode);
  }

  public String itemKey() {
    return destination.toString();
  }

  private static void requireAbsoluteSource(Path path) {
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("File write source must be absolute");
    }
  }

  private static void requirePresent(String value, String label) {
    if (value.isBlank()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
  }

  private static void requireOctalMode(String value) {
    if (!value.matches("[0-7]{3,4}")) {
      throw new IllegalArgumentException("File write mode must be octal");
    }
  }
}
