package dev.sysboot.executor;

import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import java.io.IOException;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.regex.Pattern;

final class ChecksumResolver {

  private static final Pattern SHA256_HEX = Pattern.compile("\\b([a-fA-F0-9]{64})\\b");

  private final BinaryDownloadClient downloadClient;

  ChecksumResolver() {
    this(new HttpBinaryDownloadClient());
  }

  ChecksumResolver(HttpClient httpClient) {
    this(new HttpBinaryDownloadClient(httpClient));
  }

  ChecksumResolver(BinaryDownloadClient downloadClient) {
    this.downloadClient = downloadClient;
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
    return parseSha256(downloadClient.downloadText(module.checksumUrl().orElseThrow().value()));
  }

  static String parseSha256(String body) throws IOException {
    var matcher = SHA256_HEX.matcher(body);
    if (matcher.find()) {
      return matcher.group(1).toLowerCase();
    }
    throw new IOException("Checksum document does not contain a SHA-256 digest");
  }
}
