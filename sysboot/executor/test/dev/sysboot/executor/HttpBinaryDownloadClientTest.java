package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpBinaryDownloadClientTest {

  private static final URI URL = URI.create("https://example.test/artifact");

  @TempDir Path tempDir;

  @Test
  void downloadToFileWithDigest_returnsDigestOfExactResponseBytes() throws Exception {
    byte[] body = "trusted-response".getBytes();
    HttpClient httpClient = clientReturning(response(200, URL, body, (long) body.length));
    var client = new HttpBinaryDownloadClient(httpClient, 64, 8);
    Path destination = tempDir.resolve("artifact");

    var digest = client.downloadToFileWithDigest(URL, destination);

    assertThat(digest).isEqualTo(ArtifactDigests.sha256(body));
    assertThat(java.nio.file.Files.readAllBytes(destination)).isEqualTo(body);
  }

  @Test
  void downloadToFile_whenBodyExceedsLimit_deletesPartialFile() throws Exception {
    HttpClient httpClient = clientReturning(response(200, URL, "12345".getBytes(), null));
    var client = new HttpBinaryDownloadClient(httpClient, 4, 4);
    Path destination = tempDir.resolve("artifact");

    assertThatThrownBy(() -> client.downloadToFile(URL, destination))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("maximum size");
    assertThat(destination).doesNotExist();
  }

  @Test
  void downloadToFile_whenContentLengthExceedsLimit_rejectsBeforeWrite() throws Exception {
    HttpClient httpClient = clientReturning(response(200, URL, "12345".getBytes(), 5L));
    var client = new HttpBinaryDownloadClient(httpClient, 4, 4);
    Path destination = tempDir.resolve("artifact");

    assertThatThrownBy(() -> client.downloadToFile(URL, destination))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("maximum size");
    assertThat(destination).doesNotExist();
  }

  @Test
  void downloadToFile_whenBodyIsTruncated_deletesPartialFile() throws Exception {
    HttpClient httpClient = clientReturning(response(200, URL, "123".getBytes(), 5L));
    var client = new HttpBinaryDownloadClient(httpClient, 8, 8);
    Path destination = tempDir.resolve("artifact");

    assertThatThrownBy(() -> client.downloadToFile(URL, destination))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("truncated");
    assertThat(destination).doesNotExist();
  }

  @Test
  void downloadToFile_whenRedirectDowngradesToHttp_rejectsAndCleansUp() throws Exception {
    URI insecure = URI.create("http://example.test/artifact");
    HttpClient httpClient = clientReturning(response(200, insecure, "123".getBytes(), 3L));
    var client = new HttpBinaryDownloadClient(httpClient, 8, 8);
    Path destination = tempDir.resolve("artifact");

    assertThatThrownBy(() -> client.downloadToFile(URL, destination))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("HTTPS");
    assertThat(destination).doesNotExist();
  }

  @Test
  void downloadText_whenBodyExceedsLimit_rejectsBoundedDocument() throws Exception {
    HttpClient httpClient = clientReturning(response(200, URL, "12345".getBytes(), null));
    var client = new HttpBinaryDownloadClient(httpClient, 8, 4);

    assertThatThrownBy(() -> client.downloadText(URL))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("maximum size");
  }

  @Test
  void downloadToFile_whenBodyStalls_timesOutAndDeletesPartialFile() throws Exception {
    var stalledBody = new StalledInputStream();
    HttpClient httpClient = clientReturning(response(200, URL, stalledBody, null));
    var client =
        new HttpBinaryDownloadClient(
            httpClient, 8, 8, Duration.ofMillis(50), Duration.ofMillis(50));
    Path destination = tempDir.resolve("artifact");

    assertThatThrownBy(() -> client.downloadToFile(URL, destination))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("timed out");
    assertThat(destination).doesNotExist();
    assertThat(stalledBody.closed.await(1, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void downloadToFile_whenBodyAndCloseBothBlock_timeoutStillReturnsPromptly() throws Exception {
    var blockedBody = new BlockingCloseInputStream();
    HttpClient httpClient = clientReturning(response(200, URL, blockedBody, null));
    var client =
        new HttpBinaryDownloadClient(
            httpClient, 8, 8, Duration.ofMillis(50), Duration.ofMillis(50));
    Path destination = tempDir.resolve("artifact");
    long started = System.nanoTime();

    try {
      assertThatThrownBy(() -> client.downloadToFile(URL, destination))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("timed out");
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
      assertThat(blockedBody.closeStarted.await(1, TimeUnit.SECONDS)).isTrue();
      assertThat(destination).doesNotExist();
    } finally {
      blockedBody.release.countDown();
    }
  }

  @SuppressWarnings("unchecked")
  private HttpClient clientReturning(HttpResponse<InputStream> response) throws Exception {
    HttpClient httpClient = org.mockito.Mockito.mock(HttpClient.class);
    when(httpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    return httpClient;
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<InputStream> response(int status, URI uri, byte[] body, Long contentLength) {
    return response(status, uri, new ByteArrayInputStream(body), contentLength);
  }

  @SuppressWarnings("unchecked")
  private HttpResponse<InputStream> response(
      int status, URI uri, InputStream body, Long contentLength) {
    HttpResponse<InputStream> response = org.mockito.Mockito.mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.uri()).thenReturn(uri);
    when(response.body()).thenReturn(body);
    Map<String, List<String>> values =
        contentLength == null
            ? Map.of()
            : Map.of("Content-Length", List.of(Long.toString(contentLength)));
    when(response.headers()).thenReturn(HttpHeaders.of(values, (name, value) -> true));
    return response;
  }

  private static final class StalledInputStream extends InputStream {

    private final CountDownLatch closed = new CountDownLatch(1);

    @Override
    public synchronized int read() throws IOException {
      while (closed.getCount() != 0) {
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted", e);
        }
      }
      return -1;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
      return read();
    }

    @Override
    public synchronized void close() {
      closed.countDown();
      notifyAll();
    }
  }

  private static final class BlockingCloseInputStream extends InputStream {

    private final CountDownLatch closeStarted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public int read() {
      awaitRelease();
      return -1;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
      return read();
    }

    @Override
    public void close() {
      closeStarted.countDown();
      awaitRelease();
    }

    private void awaitRelease() {
      boolean interrupted = false;
      while (true) {
        try {
          release.await();
          break;
        } catch (InterruptedException e) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
