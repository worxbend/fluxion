package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import org.junit.jupiter.api.Test;

class BinaryUrlTest {

  @Test
  void constructor_whenSchemeIsHttps_acceptsUrl() {
    var url = new BinaryUrl(URI.create("https://example.com/binary.tar.gz"));
    assertThat(url.value().getScheme()).isEqualTo("https");
  }

  @Test
  void constructor_whenSchemeIsHttp_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new BinaryUrl(URI.create("http://example.com/binary")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("https");
  }

  @Test
  void constructor_whenUriIsNull_throwsNullPointerException() {
    assertThatThrownBy(() -> new BinaryUrl(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructor_whenSchemeIsFtp_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new BinaryUrl(URI.create("ftp://example.com/binary")))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
