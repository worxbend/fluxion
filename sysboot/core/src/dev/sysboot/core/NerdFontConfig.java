package dev.sysboot.core;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record NerdFontConfig(
    String release, Path destination, boolean refreshFontCache, List<String> families) {

  public NerdFontConfig {
    Objects.requireNonNull(release);
    Objects.requireNonNull(destination);
    Objects.requireNonNull(families);
    families = List.copyOf(families);
  }
}
