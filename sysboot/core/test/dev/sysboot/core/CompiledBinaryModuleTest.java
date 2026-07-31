package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CompiledBinaryModuleTest {

  @Test
  void constructor_whenSignerFingerprintIsLowercase_normalizesFingerprint() {
    var module = module(Optional.of("a".repeat(40)));

    assertThat(module.allowedSignerFingerprint()).contains("A".repeat(40));
  }

  @Test
  void constructor_whenSignerFingerprintIsMalformed_rejectsFingerprint() {
    assertThatThrownBy(() -> module(Optional.of("not-a-fingerprint")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("40 or 64 hexadecimal");
  }

  @Test
  void constructor_whenArchivePathIsMissing_rejectsArchive() {
    assertThatThrownBy(
            () ->
                new CompiledBinaryModule(
                    new ModuleName("ripgrep"),
                    "rg",
                    new BinaryUrl(URI.create("https://example.test/rg.tar.gz")),
                    Optional.empty(),
                    Path.of("/usr/local/bin/rg"),
                    false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("archivePath");
  }

  @Test
  void constructor_whenOutputPathIsRelativeOrNotNormalized_rejectsPath() {
    assertThatThrownBy(() -> module(Path.of("bin/rg"), Optional.of("rg")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("absolute");
    assertThatThrownBy(() -> module(Path.of("/usr/local/../bin/rg"), Optional.of("rg")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("normalized");
  }

  @Test
  void constructor_whenArchivePathEscapesOrIsAbsolute_rejectsSelector() {
    assertThatThrownBy(() -> module(Path.of("/usr/local/bin/rg"), Optional.of("../rg")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("normalized relative");
    assertThatThrownBy(() -> module(Path.of("/usr/local/bin/rg"), Optional.of("/bin/rg")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("normalized relative");
  }

  @Test
  void constructor_whenInstallAndSymlinkPathsOverlap_rejectsTopology() {
    assertThatThrownBy(
            () ->
                new CompiledBinaryModule(
                    new ModuleName("ripgrep"),
                    "rg",
                    new BinaryUrl(URI.create("https://example.test/rg")),
                    Optional.of(new Checksum("sha256", "a".repeat(64))),
                    Optional.empty(),
                    Optional.empty(),
                    Path.of("/usr/local/bin/rg"),
                    Optional.empty(),
                    0,
                    Optional.of("0755"),
                    Optional.of(Path.of("/usr/local/bin")),
                    false,
                    Optional.empty(),
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not contain one another");
  }

  private CompiledBinaryModule module(Optional<String> signerFingerprint) {
    return module(Path.of("/usr/local/bin/rg"), Optional.of("rg"), signerFingerprint);
  }

  private CompiledBinaryModule module(Path installPath, Optional<String> archivePath) {
    return module(installPath, archivePath, Optional.empty());
  }

  private CompiledBinaryModule module(
      Path installPath, Optional<String> archivePath, Optional<String> signerFingerprint) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(URI.create("https://example.test/rg.tar.gz")),
        Optional.empty(),
        Optional.empty(),
        Optional.of(new BinaryUrl(URI.create("https://example.test/rg.tar.gz.asc"))),
        installPath,
        archivePath,
        0,
        Optional.of("0755"),
        Optional.empty(),
        false,
        Optional.empty(),
        Optional.empty(),
        signerFingerprint);
  }
}
