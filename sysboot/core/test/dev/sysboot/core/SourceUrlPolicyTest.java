package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceUrlPolicyTest {

  @Test
  void aptRepositoryUri_acceptsDebAndDebSrcOptionSyntax() {
    assertThat(
            SourceUrlPolicy.aptRepositoryUri(
                "deb [arch=amd64 signed-by=/keys/repo.gpg] https://example.test/repo stable main"))
        .isEqualTo(URI.create("https://example.test/repo"));
    assertThat(
            SourceUrlPolicy.aptRepositoryUri(
                "deb-src [arch=amd64] https://example.test/source stable main"))
        .isEqualTo(URI.create("https://example.test/source"));
  }

  @Test
  void aptRepositoryUri_rejectsHttpAndUserInfo() {
    assertThatThrownBy(
            () -> SourceUrlPolicy.aptRepositoryUri("deb http://example.test/repo stable main"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HTTPS");
    assertThatThrownBy(
            () ->
                SourceUrlPolicy.aptRepositoryUri(
                    "deb https://user:secret@example.test/repo stable main"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user-info");
  }

  @Test
  void authenticatedAptSource_allowsOnlyArchAndMatchingSignedBy() {
    assertThat(
            SourceUrlPolicy.aptRepositoryUri(
                "deb [arch=amd64,arm64 signed-by=/etc/apt/keyrings/example.gpg]"
                    + " https://example.test/repo stable main"))
        .isEqualTo(URI.create("https://example.test/repo"));

    SourceUrlPolicy.requireAuthenticatedAptSource(
        "deb [arch=amd64,arm64 signed-by=/etc/apt/keyrings/example.gpg]"
            + " https://example.test/repo stable main",
        Path.of("/etc/apt/keyrings/example.gpg"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "deb https://example.test/repo stable main",
        "deb [trusted=yes signed-by=/etc/apt/keyrings/example.gpg]"
            + " https://example.test/repo stable main",
        "deb [allow-insecure=yes signed-by=/etc/apt/keyrings/example.gpg]"
            + " https://example.test/repo stable main",
        "deb [signed-by=/etc/apt/keyrings/example.gpg"
            + " signed-by=/etc/apt/keyrings/example.gpg] https://example.test/repo stable main"
      })
  void authenticatedAptSource_rejectsMissingDangerousOrRepeatedOptions(String source) {
    assertThatThrownBy(
            () ->
                SourceUrlPolicy.requireAuthenticatedAptSource(
                    source, Path.of("/etc/apt/keyrings/example.gpg")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void authenticatedAptSource_rejectsSignedByThatDoesNotMatchConfiguredKeyring() {
    assertThatThrownBy(
            () ->
                SourceUrlPolicy.requireAuthenticatedAptSource(
                    "deb [signed-by=/etc/apt/keyrings/other.gpg]"
                        + " https://example.test/repo stable main",
                    Path.of("/etc/apt/keyrings/example.gpg")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("match");
  }
}
