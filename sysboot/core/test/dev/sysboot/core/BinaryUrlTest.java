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

  @Test
  void constructor_whenUserInfoPresent_throwsIllegalArgumentException() {
    assertThatThrownBy(
            () ->
                new BinaryUrl(
                    URI.create(
                        "https://user:password@example.com/binary?token=query-secret#private")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("user-info")
        .hasMessageContaining("https://example.com/binary")
        .hasMessageNotContaining("user:password")
        .hasMessageNotContaining("query-secret")
        .hasMessageNotContaining("private")
        .hasMessageNotContaining("token=");
  }

  @Test
  void constructor_whenHostMissing_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new BinaryUrl(URI.create("https:///binary")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("host");
  }

  @Test
  void stateSource_whenUrlHasQueryAndFragment_stripsNonPublicComponents() {
    var url =
        new BinaryUrl(
            URI.create("https://downloads.example.test:8443/tool.bin?token=secret#release"));

    assertThat(url.stateSource()).isEqualTo("https://downloads.example.test:8443/tool.bin");
    assertThat(url.value().getQuery()).isEqualTo("token=secret");
  }
}
