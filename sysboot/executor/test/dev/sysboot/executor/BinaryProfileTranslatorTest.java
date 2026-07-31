package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Golden tests on the generated profile.
 *
 * <p>A translation layer that silently produces a wrong profile is worse than no translation, so
 * the output shape is pinned against binstaller's documented manifest reference, and every case
 * binstaller cannot express is asserted to refuse rather than guess.
 */
class BinaryProfileTranslatorTest {

  @Test
  void aZipArchiveTranslatesEvenThoughFluxionsOwnInstallerRejectsZip() {
    // This is the point of delegating: the shipped Fedora profile falls back to
    // `curl | unzip | mv | chmod` for yazi purely because Fluxion only extracts tar.gz.
    Object result =
        BinaryProfileTranslator.translate(
            module("yazi", "https://example.com/yazi-linux.zip")
                .withArchivePath("yazi-linux/yazi")
                .build());

    assertThat(result).isInstanceOf(String.class);
    String yaml = (String) result;
    assertThat(yaml).contains("type: \"zip\"");
    assertThat(yaml).contains("from: \"yazi-linux/yazi\"");
    assertThat(yaml).contains("to: \"bin/yazi\"");
  }

  @Test
  void aTarXzArchiveAlsoTranslates() {
    Object result =
        BinaryProfileTranslator.translate(
            module("zig", "https://ziglang.org/download/0.15.2/zig-linux.tar.xz")
                .withArchivePath("zig-linux/zig")
                .build());

    assertThat((String) result).contains("type: \"tar.xz\"");
  }

  @Test
  void aPlainBinaryHasNoArchiveBlock() {
    Object result =
        BinaryProfileTranslator.translate(
            module("kubectl", "https://dl.k8s.io/release/v1.30.2/bin/linux/amd64/kubectl").build());

    assertThat((String) result).doesNotContain("archive").contains("filename: \"kubectl\"");
  }

  @Test
  void theProfileMatchesBinstallersDocumentedTopLevelShape() {
    String yaml =
        (String) BinaryProfileTranslator.translate(module("rg", "https://e.com/rg").build());

    assertThat(yaml)
        .contains("apiVersion: \"binstaller.io/v1alpha1\"")
        .contains("kind: \"BinaryDistributionProfile\"")
        .contains("kind: \"binary-tool\"")
        .contains(
            "appsDir: \""
                + Path.of(System.getProperty("user.home"), ".apps").toAbsolutePath().normalize()
                + "\"")
        .contains("installDir: \"${appsDir}/rg\"");
  }

  @Test
  void anAbsolutePrivilegedInstallPathRefusesDelegation() {
    Object result =
        BinaryProfileTranslator.translate(
            module("rg", "https://e.com/rg").withInstallPath("/usr/local/bin/rg").build());

    assertThat(result).isInstanceOf(BinaryProfileTranslator.Refusal.class);
    assertThat(((BinaryProfileTranslator.Refusal) result).reason()).contains("privileged symlinks");
  }

  @Test
  void anInstallInsideTheAppsDirNeedsNoPrivilegedSymlink() {
    Path appsInstall =
        Path.of(System.getProperty("user.home"), ".apps", "rg", "bin", "rg")
            .toAbsolutePath()
            .normalize();
    String yaml =
        (String)
            BinaryProfileTranslator.translate(
                module("rg", "https://e.com/rg").withInstallPath(appsInstall.toString()).build());

    assertThat(yaml).contains("allowSudoSymlinks: false").doesNotContain("sudo: true");
  }

  @Test
  void aNonCanonicalPathInsideTheAppsDirStillGetsAnUnprivilegedSymlink() {
    Path appsInstall =
        Path.of(System.getProperty("user.home"), ".apps", "shared", "bin", "rg")
            .toAbsolutePath()
            .normalize();
    String yaml =
        (String)
            BinaryProfileTranslator.translate(
                module("rg", "https://e.com/rg").withInstallPath(appsInstall.toString()).build());

    assertThat(yaml)
        .contains("path: \"" + appsInstall + "\"")
        .contains("sudo: false")
        .contains("allowSudoSymlinks: false");
  }

  @Test
  void aDeceptiveAppsPathOutsideTheConfiguredRootRefusesDelegation() {
    Object result =
        BinaryProfileTranslator.translate(
            module("rg", "https://e.com/rg")
                .withInstallPath("/tmp/not-home/.apps/rg/bin/rg")
                .build());

    assertThat(result).isInstanceOf(BinaryProfileTranslator.Refusal.class);
  }

  @Test
  void aChecksumUrlBecomesChecksumDiscovery() {
    String yaml =
        (String)
            BinaryProfileTranslator.translate(
                module("rg", "https://e.com/rg")
                    .withChecksumUrl("https://e.com/SHA256SUMS")
                    .build());

    assertThat(yaml).contains("discover").contains("type: \"sha256sum\"");
  }

  @Test
  void aDetachedSignatureRefusesRatherThanDroppingTheCheck() {
    Object result =
        BinaryProfileTranslator.translate(
            module("rg", "https://e.com/rg").withSignatureUrl("https://e.com/rg.asc").build());

    assertThat(result).isInstanceOf(BinaryProfileTranslator.Refusal.class);
    assertThat(((BinaryProfileTranslator.Refusal) result).reason())
        .as("silently dropping a signature check the profile asked for would be a security defect")
        .contains("detached GPG signature");
  }

  @Test
  void aNonSha256ChecksumRefuses() {
    Object result =
        BinaryProfileTranslator.translate(
            module("rg", "https://e.com/rg").withChecksum("SHA-512", "a".repeat(128)).build());

    assertThat(result).isInstanceOf(BinaryProfileTranslator.Refusal.class);
  }

  @Test
  void anArchiveWithStripComponentsRefusesRatherThanChangingSelectionSemantics() {
    Object result =
        BinaryProfileTranslator.translate(
            module("t", "https://e.com/t.tar.gz")
                .withArchivePath("bin/t")
                .withStripComponents(1)
                .build());

    assertThat(result).isInstanceOf(BinaryProfileTranslator.Refusal.class);
    assertThat(((BinaryProfileTranslator.Refusal) result).reason()).contains("stripComponents");
  }

  @Test
  void aPlainBinaryWithStripComponentsAlsoRefusesDelegation() {
    Object result =
        BinaryProfileTranslator.translate(
            module("t", "https://e.com/t").withStripComponents(1).build());

    assertThat(result).isInstanceOf(BinaryProfileTranslator.Refusal.class);
    assertThat(((BinaryProfileTranslator.Refusal) result).reason()).contains("stripComponents");
  }

  @Test
  void keyOrderIsDeterministicSoTheProfileCanBeFingerprintedAndDiffed() {
    // Map.of randomises iteration order per JVM. Generated output that changes between runs would
    // break reproducibility and any fingerprint taken over it.
    var module = module("yazi", "https://e.com/yazi.zip").withArchivePath("y/yazi").build();

    String first = (String) BinaryProfileTranslator.translate(module);
    String second = (String) BinaryProfileTranslator.translate(module);

    assertThat(first).isEqualTo(second);
    assertThat(first.indexOf("type:")).isLessThan(first.indexOf("extract:"));
    assertThat(first.indexOf("from:")).isLessThan(first.indexOf("to:"));
  }

  private Builder module(String name, String url) {
    return new Builder(name, url);
  }

  /** Keeps the fifteen-argument record out of every test. */
  private static final class Builder {
    private final String name;
    private final String url;
    private Optional<Checksum> checksum = Optional.empty();
    private Optional<BinaryUrl> checksumUrl = Optional.empty();
    private Optional<BinaryUrl> signatureUrl = Optional.empty();
    private Path installPath;
    private Optional<String> archivePath = Optional.empty();
    private int stripComponents;

    Builder(String name, String url) {
      this.name = name;
      this.url = url;
      this.installPath =
          Path.of(System.getProperty("user.home"), ".apps", name, "bin", name)
              .toAbsolutePath()
              .normalize();
    }

    Builder withArchivePath(String value) {
      this.archivePath = Optional.of(value);
      return this;
    }

    Builder withInstallPath(String value) {
      this.installPath = Path.of(value);
      return this;
    }

    Builder withStripComponents(int value) {
      this.stripComponents = value;
      return this;
    }

    Builder withChecksum(String algorithm, String value) {
      this.checksum = Optional.of(new Checksum(algorithm, value));
      return this;
    }

    Builder withChecksumUrl(String value) {
      this.checksumUrl = Optional.of(new BinaryUrl(URI.create(value)));
      return this;
    }

    Builder withSignatureUrl(String value) {
      this.signatureUrl = Optional.of(new BinaryUrl(URI.create(value)));
      return this;
    }

    CompiledBinaryModule build() {
      return new CompiledBinaryModule(
          new ModuleName(name),
          name,
          new BinaryUrl(URI.create(url)),
          checksum,
          checksumUrl,
          signatureUrl,
          installPath,
          archivePath,
          stripComponents,
          Optional.of("0755"),
          Optional.empty(),
          false,
          Optional.empty(),
          Optional.empty());
    }
  }
}
