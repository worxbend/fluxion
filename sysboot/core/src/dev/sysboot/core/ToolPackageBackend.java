package dev.sysboot.core;

/** Ecosystem installer behind a {@code tool-packages} step. */
public enum ToolPackageBackend {
  /** Prebuilt Rust binaries. Far faster than `cargo install`, which compiles from source. */
  CARGO_BINSTALL("cargo-binstall", "cargo-binstall"),
  /** Compiles from source; kept for crates that publish no binaries. */
  CARGO("cargo", "cargo"),
  SNAP("snap", "snap"),
  PIPX("pipx", "pipx"),
  UV_TOOL("uv-tool", "uv"),
  NPM_GLOBAL("npm-global", "npm"),
  GO_INSTALL("go-install", "go");

  private final String id;
  private final String binary;

  ToolPackageBackend(String id, String binary) {
    this.id = id;
    this.binary = binary;
  }

  /** Identifier used in YAML. */
  public String id() {
    return id;
  }

  /** Executable that must be present on the host. */
  public String binary() {
    return binary;
  }

  public static java.util.Optional<ToolPackageBackend> fromId(String value) {
    if (value == null) {
      return java.util.Optional.empty();
    }
    String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
    return java.util.Arrays.stream(values())
        .filter(backend -> backend.id.equals(normalized))
        .findFirst();
  }
}
