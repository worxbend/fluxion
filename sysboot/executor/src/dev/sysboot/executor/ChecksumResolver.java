package dev.sysboot.executor;

import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Pattern;

final class ChecksumResolver {

  private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(1);
  private static final Pattern SHA256_HEX = Pattern.compile("\\b([a-fA-F0-9]{64})\\b");

  private final HttpClient httpClient;

  ChecksumResolver() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
  }

  ChecksumResolver(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  Optional<Checksum> resolve(CompiledBinaryModule module) throws IOException {
    if (module.checksum().isPresent()) {
      return module.checksum();
    }
    if (module.checksumUrl().isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Checksum("SHA-256", downloadChecksum(module)));
  }

  private String downloadChecksum(CompiledBinaryModule module) throws IOException {
    var request =
        HttpRequest.newBuilder(module.checksumUrl().orElseThrow().value())
            .timeout(DOWNLOAD_TIMEOUT)
            .GET()
            .build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IOException("Checksum download failed with HTTP " + response.statusCode());
      }
      return parseSha256(response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Checksum download interrupted", e);
    }
  }

  static String parseSha256(String body) throws IOException {
    var matcher = SHA256_HEX.matcher(body);
    if (matcher.find()) {
      return matcher.group(1).toLowerCase();
    }
    throw new IOException("Checksum document does not contain a SHA-256 digest");
  }
}
