package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemoteScriptTrustTest {

  private static final Sha256Digest DIGEST =
      new Sha256Digest("0000000000000000000000000000000000000000000000000000000000000000");

  @Test
  void remoteShellScript_withoutDigest_isRejected() {
    assertThatThrownBy(
            () -> remoteScript(URI.create("https://example.test/install.sh"), Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("require sha256");
  }

  @Test
  void remoteShellScript_withHttpOrUserInfo_isRejected() {
    for (String url :
        List.of("http://example.test/install.sh", "https://token@example.test/install.sh")) {
      assertThatThrownBy(() -> remoteScript(URI.create(url), Optional.of(DIGEST)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("HTTPS without user-info");
    }
  }

  @Test
  void remoteShellScript_withSignedUrl_exposesOnlyPublicUrlAsKey() {
    URI signedUrl =
        URI.create("https://example.test/install.sh?X-Amz-Signature=do-not-print#private-fragment");

    ShellScriptItem item = remoteScript(signedUrl, Optional.of(DIGEST));

    assertThat(item.key()).isEqualTo("https://example.test/install.sh");
    assertThat(item.url()).contains(signedUrl);
  }

  @Test
  void localShellScript_withRemoteDigest_isRejected() {
    assertThatThrownBy(
            () ->
                new ShellScriptItem(
                    "local",
                    Optional.of(new ScriptPath(Path.of("local.sh"))),
                    Optional.empty(),
                    List.of(),
                    Optional.empty(),
                    List.of(),
                    false,
                    List.of(0),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Duration.ofSeconds(1),
                    Optional.of(DIGEST)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("local scripts must omit");
  }

  @Test
  void ohMyZsh_withMutableRevision_isRejected() {
    assertThatThrownBy(
            () ->
                new OhMyZshModule(
                    new ModuleName("oh-my-zsh"),
                    Path.of(".oh-my-zsh"),
                    "master",
                    DIGEST,
                    Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("full 40-character commit");
  }

  private ShellScriptItem remoteScript(URI url, Optional<Sha256Digest> digest) {
    return new ShellScriptItem(
        "remote",
        Optional.empty(),
        Optional.of(url),
        List.of(),
        Optional.empty(),
        List.of(),
        false,
        List.of(0),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Duration.ofSeconds(1),
        digest);
  }
}
