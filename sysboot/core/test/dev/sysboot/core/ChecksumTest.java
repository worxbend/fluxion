package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChecksumTest {

  @ParameterizedTest
  @ValueSource(strings = {"sha256", "SHA256", "sha-256", "SHA-256"})
  void constructor_whenAlgorithmIsSha256Alias_normalizesCanonicalName(String algorithm) {
    var checksum = new Checksum(algorithm, "A".repeat(64));

    assertThat(checksum.algorithm()).isEqualTo("SHA-256");
    assertThat(checksum.value()).isEqualTo("a".repeat(64));
    assertThat(checksum.hasValidSha256Value()).isTrue();
  }
}
