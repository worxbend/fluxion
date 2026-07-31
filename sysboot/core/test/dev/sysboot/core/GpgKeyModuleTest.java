package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GpgKeyModuleTest {

  private static final String FINGERPRINT = "A".repeat(40);

  @Test
  void signedUrl_keepsRequestUrlButUsesPublicStableIdentity() {
    var key =
        new GpgKeyModule.GpgKey(
            "https://example.test/key.asc?token=secret#fragment", Optional.empty(), FINGERPRINT);

    assertThat(key.url()).endsWith("?token=secret#fragment");
    assertThat(key.publicUrl()).isEqualTo("https://example.test/key.asc");
    assertThat(key.displayName()).isEqualTo("https://example.test/key.asc");
    assertThat(key.itemKey()).isEqualTo("fingerprint:" + FINGERPRINT);
  }

  @Test
  void keyringPath_isBothStableIdentityAndDisplayName() {
    Path keyring = Path.of("/etc/apt/keyrings/example.gpg");
    var key =
        new GpgKeyModule.GpgKey(
            "https://example.test/key.asc?token=secret", Optional.of(keyring), FINGERPRINT);

    assertThat(key.itemKey()).isEqualTo(keyring.toString());
    assertThat(key.displayName()).isEqualTo(keyring.toString());
  }
}
