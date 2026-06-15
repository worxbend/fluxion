package dev.sysboot.executor;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

public final class CompiledBinaryArtifactFormat {

  private static final Set<String> UNSUPPORTED_ARCHIVE_SUFFIXES =
      Set.of(".zip", ".tar.xz", ".tar.bz2", ".tar", ".gz", ".xz", ".bz2", ".7z", ".rar");

  private CompiledBinaryArtifactFormat() {}

  public static boolean isSupported(URI uri) {
    String path = uri.getPath().toLowerCase(Locale.ROOT);
    if (path.endsWith(".tar.gz") || path.endsWith(".tgz")) {
      return true;
    }
    return UNSUPPORTED_ARCHIVE_SUFFIXES.stream().noneMatch(path::endsWith);
  }

  public static String supportedFormats() {
    return ".tar.gz, .tgz, or a plain binary URL";
  }
}
