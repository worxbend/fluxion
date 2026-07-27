package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

/**
 * Suffix matching here is order-sensitive in a way that is easy to get wrong: {@code .tar.gz} also
 * ends with {@code .gz} and {@code .tar.xz} with {@code .xz}. Testing the unsupported list first
 * rejects exactly the formats that are supported, which briefly broke every existing tar.gz step.
 */
class CompiledBinaryArtifactFormatTest {

  @Test
  void tarGzIsSupportedAndExtractableWithoutAnyExternalTool() {
    URI uri = URI.create("https://e.com/tool.tar.gz");

    assertThat(CompiledBinaryArtifactFormat.isSupported(uri)).isTrue();
    assertThat(CompiledBinaryArtifactFormat.isNative(uri)).isTrue();
    assertThat(CompiledBinaryArtifactFormat.requiresDelegation(uri)).isFalse();
  }

  @Test
  void zipAndTarXzAreSupportedButOnlyThroughBinstaller() {
    for (String url : new String[] {"https://e.com/t.zip", "https://e.com/t.tar.xz"}) {
      URI uri = URI.create(url);
      assertThat(CompiledBinaryArtifactFormat.isSupported(uri)).as(url).isTrue();
      assertThat(CompiledBinaryArtifactFormat.requiresDelegation(uri)).as(url).isTrue();
      assertThat(CompiledBinaryArtifactFormat.isNative(uri)).as(url).isFalse();
    }
  }

  @Test
  void aPlainBinaryUrlIsNative() {
    URI uri = URI.create("https://dl.k8s.io/release/v1.30.2/bin/linux/amd64/kubectl");

    assertThat(CompiledBinaryArtifactFormat.isSupported(uri)).isTrue();
    assertThat(CompiledBinaryArtifactFormat.isNative(uri)).isTrue();
  }

  @Test
  void formatsNeitherFluxionNorBinstallerHandleAreStillRejected() {
    for (String url :
        new String[] {
          "https://e.com/t.7z",
          "https://e.com/t.rar",
          "https://e.com/t.tar.bz2",
          "https://e.com/t.gz"
        }) {
      assertThat(CompiledBinaryArtifactFormat.isSupported(URI.create(url))).as(url).isFalse();
    }
  }
}
