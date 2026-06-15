package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChecksumResolverTest {

  @Test
  void parseSha256_whenSha256sumFormat_returnsDigest() throws Exception {
    String digest = "ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890ABCDEF1234567890";

    String parsed = ChecksumResolver.parseSha256(digest + "  fluxion.tar.gz\n");

    assertThat(parsed).isEqualTo(digest.toLowerCase());
  }

  @Test
  void parseSha256_whenNoDigest_throwsIOException() {
    assertThatThrownBy(() -> ChecksumResolver.parseSha256("not a checksum"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("SHA-256");
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolve_whenChecksumUrlPresent_downloadsAndParsesDigest() throws Exception {
    String digest = "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);
    HttpResponse<String> response = org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.body()).thenReturn(digest + "  rg.tar.gz\n");
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);

    var resolver = new ChecksumResolver(httpClient);

    assertThat(resolver.resolve(module()).orElseThrow().value()).isEqualTo(digest);
  }

  private static CompiledBinaryModule module() throws Exception {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(new URI("https://example.test/rg.tar.gz")),
        Optional.empty(),
        Optional.of(new BinaryUrl(new URI("https://example.test/rg.sha256"))),
        Path.of("/usr/local/bin/rg"),
        false);
  }
}
