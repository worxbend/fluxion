package dev.sysboot.core;

import java.util.regex.Pattern;

final class ToolPackageIdentifierPolicy {

  private static final Pattern CARGO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
  private static final Pattern SNAP = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");
  private static final Pattern PYTHON = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final Pattern NPM =
      Pattern.compile("(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*", Pattern.CASE_INSENSITIVE);
  private static final Pattern GO =
      Pattern.compile(
          "(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\\.)+[A-Za-z]{2,}"
              + "(?:/[A-Za-z0-9][A-Za-z0-9._~-]*)+");
  private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9.!+_-]*");
  private static final Pattern SNAP_CHANNEL =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)?");

  private ToolPackageIdentifierPolicy() {}

  static void requireSafe(ToolPackageBackend backend, ToolPackagesModule.ToolPackage toolPackage) {
    Pattern identifier =
        switch (backend) {
          case CARGO_BINSTALL, CARGO -> CARGO;
          case SNAP -> SNAP;
          case PIPX, UV_TOOL -> PYTHON;
          case NPM_GLOBAL -> NPM;
          case GO_INSTALL -> GO;
        };
    if (isAlternateSource(toolPackage.name())
        || !identifier.matcher(toolPackage.name()).matches()) {
      throw new IllegalArgumentException(
          backend.id()
              + " package must be a registry identifier, not an option, path, URL, or alternate"
              + " source: "
              + toolPackage.name());
    }
    toolPackage.version().ifPresent(version -> requireVersion(backend, version));
  }

  private static boolean isAlternateSource(String value) {
    String lower = value.toLowerCase(java.util.Locale.ROOT);
    return value.startsWith("/")
        || value.startsWith("./")
        || value.startsWith("../")
        || value.startsWith("~/")
        || value.contains("://")
        || lower.endsWith(".whl")
        || lower.endsWith(".tar.gz")
        || lower.endsWith(".tgz")
        || lower.endsWith(".zip");
  }

  private static void requireVersion(ToolPackageBackend backend, String version) {
    Pattern allowed = backend == ToolPackageBackend.SNAP ? SNAP_CHANNEL : VERSION;
    if (!allowed.matcher(version).matches()) {
      throw new IllegalArgumentException(
          backend.id()
              + " version must be a registry version or channel, not an option, path, URL, or"
              + " alternate source");
    }
  }
}
