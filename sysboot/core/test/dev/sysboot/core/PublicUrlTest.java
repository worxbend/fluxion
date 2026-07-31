package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;

class PublicUrlTest {

  @Test
  void from_removesRequestQueryAndFragment() {
    assertThat(PublicUrl.from(URI.create("https://example.test/tool?token=secret#download")))
        .isEqualTo("https://example.test/tool");
  }

  @Test
  void from_removesLegacyUserInfo() {
    assertThat(PublicUrl.from("https://user:secret@example.test/tool"))
        .isEqualTo("https://example.test/tool");
  }

  @Test
  void from_preservesAtSignOutsideAuthority() {
    assertThat(PublicUrl.from("https://example.test/releases/user@host/tool"))
        .isEqualTo("https://example.test/releases/user@host/tool");
  }
}
