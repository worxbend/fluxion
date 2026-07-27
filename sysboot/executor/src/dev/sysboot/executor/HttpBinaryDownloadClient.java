package dev.sysboot.executor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

final class HttpBinaryDownloadClient implements BinaryDownloadClient {

  private static final Duration FILE_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration TEXT_TIMEOUT = Duration.ofMinutes(1);

  private final HttpClient httpClient;

  HttpBinaryDownloadClient() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
  }

  HttpBinaryDownloadClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public void downloadToFile(URI url, Path destination) throws IOException {
    var request = HttpRequest.newBuilder(url).timeout(FILE_TIMEOUT).GET().build();
    try {
      HttpResponse<Path> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
      requireOk(response.statusCode(), "Download failed", url);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Download interrupted", e);
    }
  }

  @Override
  public String downloadText(URI url) throws IOException {
    var request = HttpRequest.newBuilder(url).timeout(TEXT_TIMEOUT).GET().build();
    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      requireOk(response.statusCode(), "Checksum download failed", url);
      return response.body();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Checksum download interrupted", e);
    }
  }

  private void requireOk(int statusCode, String message, URI url) throws IOException {
    if (statusCode != 200) {
      throw new IOException(message + " with HTTP " + statusCode + " for " + url);
    }
  }
}
