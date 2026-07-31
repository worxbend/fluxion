package dev.sysboot.executor;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which artifact formats a {@code compiled-binary} step can install.
 *
 * <p>Fluxion's own extractor only ever handled {@code tar.gz}. binstaller handles {@code zip} and
 * {@code tar.xz} as well, so those are now installable by delegation — which is why the shipped
 * Fedora profile no longer needs hand-written {@code curl | unzip | mv | chmod} for yazi and zig.
 * They are reported as delegation-only so a profile that runs where binstaller cannot be obtained
 * still gets a warning rather than a surprise at apply time.
 */
public final class CompiledBinaryArtifactFormat {

  private static final Set<String> NATIVE_SUFFIXES = Set.of(".tar.gz", ".tgz");
  private static final Set<String> DELEGATED_SUFFIXES = Set.of(".zip", ".tar.xz");
  private static final Set<String> UNSUPPORTED_SUFFIXES =
      Set.of(".tar.bz2", ".tar", ".gz", ".xz", ".bz2", ".7z", ".rar");

  private CompiledBinaryArtifactFormat() {}

  /**
   * Installable, whether natively or through binstaller.
   *
   * <p>Known formats are matched first: {@code .tar.xz} also ends with {@code .xz} and {@code
   * .tar.gz} with {@code .gz}, so testing the unsupported list first rejects the very formats that
   * are supported.
   */
  public static boolean isSupported(URI uri) {
    String path = path(uri);
    if (isArchive(path)) {
      return true;
    }
    return UNSUPPORTED_SUFFIXES.stream().noneMatch(path::endsWith);
  }

  /** Installable only by delegating to binstaller; Fluxion cannot extract it itself. */
  public static boolean requiresDelegation(URI uri) {
    return DELEGATED_SUFFIXES.stream().anyMatch(path(uri)::endsWith);
  }

  /** Extractable by Fluxion without any external tool. */
  public static boolean isNative(URI uri) {
    String path = path(uri);
    if (NATIVE_SUFFIXES.stream().anyMatch(path::endsWith)) {
      return true;
    }
    return !isArchive(path) && UNSUPPORTED_SUFFIXES.stream().noneMatch(path::endsWith);
  }

  public static boolean isArchive(URI uri) {
    return isArchive(path(uri));
  }

  private static boolean isArchive(String path) {
    return DELEGATED_SUFFIXES.stream().anyMatch(path::endsWith)
        || NATIVE_SUFFIXES.stream().anyMatch(path::endsWith);
  }

  public static String supportedFormats() {
    return ".tar.gz, .tgz, .zip, .tar.xz, or a plain binary URL";
  }

  private static String path(URI uri) {
    return uri.getPath().toLowerCase(Locale.ROOT);
  }
}
