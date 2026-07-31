package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenPgpKeyDecoderTest {

  @TempDir Path tempDirectory;

  @Test
  void asciiArmorIsDecodedFromTheRootOwnedStageWithoutAnotherMutableOutput() throws Exception {
    Path source =
        Files.writeString(
            tempDirectory.resolve("key.asc"),
            """
            -----BEGIN PGP PUBLIC KEY BLOCK-----

            aGVsbG8=
            -----END PGP PUBLIC KEY BLOCK-----
            """);

    assertThat(OpenPgpKeyDecoder.decode(source, 1024)).isEqualTo("hello".getBytes());
  }

  @Test
  void binaryKeyMaterialIsPreservedExactly() throws Exception {
    byte[] binary = {1, 2, 3, 4, (byte) 0xff};
    Path source = Files.write(tempDirectory.resolve("key.gpg"), binary);

    assertThat(OpenPgpKeyDecoder.decode(source, 1024)).isEqualTo(binary);
  }

  @Test
  void malformedArmorFailsClosed() throws Exception {
    Path source =
        Files.writeString(
            tempDirectory.resolve("bad.asc"),
            """
            -----BEGIN PGP PUBLIC KEY BLOCK-----

            not base64!
            -----END PGP PUBLIC KEY BLOCK-----
            """);

    assertThatThrownBy(() -> OpenPgpKeyDecoder.decode(source, 1024))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("Malformed");
  }
}
