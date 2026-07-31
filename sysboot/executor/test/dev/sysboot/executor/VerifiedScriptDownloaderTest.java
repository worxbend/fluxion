package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class VerifiedScriptDownloaderTest {

  private static final URI URL = URI.create("https://example.test/install.sh");

  @TempDir Path tempDirectory;

  private final HttpClient httpClient = mock(HttpClient.class);
  private TestSubscription lastSubscription;

  @Test
  void download_matchingDigest_streamsVerifiedExecutable() throws Exception {
    byte[] script = "#!/bin/sh\necho ok\n".getBytes();
    stubResponse(200, URL, script);
    var downloader = downloader(1024, Duration.ofSeconds(3));

    Path downloaded = downloader.download(URL, digest(script));

    assertThat(Files.readAllBytes(downloaded)).isEqualTo(script);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(downloaded)))
        .isEqualTo("rwx------");
    var request = ArgumentCaptor.forClass(HttpRequest.class);
    verify(httpClient).send(request.capture(), anyPathHandler());
    assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(3));
  }

  @Test
  void download_digestMismatch_deletesTemporaryFile() throws Exception {
    stubResponse(200, URL, "changed upstream".getBytes());

    assertThatThrownBy(
            () -> downloader(1024, Duration.ofSeconds(3)).download(URL, digest("expected")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("SHA-256 mismatch");
    assertTempDirectoryEmpty();
  }

  @Test
  void download_oversizedBody_abortsAndDeletesTemporaryFile() throws Exception {
    stubResponse(200, URL, new byte[9]);

    assertThatThrownBy(
            () -> downloader(8, Duration.ofSeconds(3)).download(URL, digest(new byte[9])))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("maximum size");
    assertTempDirectoryEmpty();
  }

  @Test
  void download_nonSuccessStatus_deletesTemporaryFile() throws Exception {
    stubResponse(503, URL, "unavailable".getBytes());

    assertThatThrownBy(
            () -> downloader(1024, Duration.ofSeconds(3)).download(URL, digest("unavailable")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTP 503");
    assertThat(lastSubscription.cancelled).isTrue();
    assertTempDirectoryEmpty();
  }

  @Test
  void download_postHeaderBodyNeverCompletes_timesOutCancelsAndDeletesTemporaryFile()
      throws Exception {
    when(httpClient.send(any(), anyPathHandler()))
        .thenAnswer(
            invocation -> {
              HttpResponse.BodyHandler<Path> handler = invocation.getArgument(1);
              HttpResponse.BodySubscriber<Path> subscriber =
                  handler.apply(responseInfo(200, OptionalLong.empty()));
              lastSubscription = new TestSubscription();
              subscriber.onSubscribe(lastSubscription);
              return awaitBody(subscriber);
            });

    assertThatThrownBy(
            () ->
                downloader(1024, Duration.ofSeconds(3), Duration.ofMillis(20))
                    .download(URL, digest("body")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("timed out");
    assertThat(lastSubscription.cancelled).isTrue();
    assertTempDirectoryEmpty();
  }

  @Test
  void download_contentLengthExceedsLimit_rejectsBeforeBodyAndDeletesTemporaryFile()
      throws Exception {
    stubResponse(200, URL, new byte[9], OptionalLong.of(9));

    assertThatThrownBy(
            () -> downloader(8, Duration.ofSeconds(3)).download(URL, digest(new byte[9])))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("maximum size");
    assertThat(lastSubscription.cancelled).isTrue();
    assertTempDirectoryEmpty();
  }

  @Test
  void download_bodyShorterThanContentLength_rejectsTruncationAndDeletesTemporaryFile()
      throws Exception {
    byte[] body = "short".getBytes();
    stubResponse(200, URL, body, OptionalLong.of(body.length + 3L));

    assertThatThrownBy(() -> downloader(1024, Duration.ofSeconds(3)).download(URL, digest(body)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("truncated");
    assertTempDirectoryEmpty();
  }

  @Test
  void download_untrustedInitialUrl_isRejectedBeforeNetworkOrTempCreation() throws Exception {
    for (URI url :
        List.of(
            URI.create("http://example.test/install.sh"),
            URI.create("https://token@example.test/install.sh"))) {
      assertThatThrownBy(
              () -> downloader(1024, Duration.ofSeconds(3)).download(url, digest("body")))
          .isInstanceOf(IOException.class)
          .hasMessageContaining("HTTPS without user-info");
    }
    verifyNoInteractions(httpClient);
    assertTempDirectoryEmpty();
  }

  @Test
  void download_redirectToUntrustedUrl_isRejectedAndCleaned() throws Exception {
    stubResponse(200, URI.create("http://example.test/install.sh"), "body".getBytes());

    assertThatThrownBy(() -> downloader(1024, Duration.ofSeconds(3)).download(URL, digest("body")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTPS without user-info");
    assertTempDirectoryEmpty();
  }

  @Test
  void download_redirectToUserInfoUrl_isRejectedAndCleaned() throws Exception {
    stubResponse(200, URI.create("https://token@example.test/install.sh"), "body".getBytes());

    assertThatThrownBy(() -> downloader(1024, Duration.ofSeconds(3)).download(URL, digest("body")))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("HTTPS without user-info");
    assertTempDirectoryEmpty();
  }

  private VerifiedScriptDownloader downloader(long maxBytes, Duration timeout) {
    return new VerifiedScriptDownloader(httpClient, tempDirectory, maxBytes, timeout);
  }

  private VerifiedScriptDownloader downloader(
      long maxBytes, Duration requestTimeout, Duration bodyTimeout) {
    return new VerifiedScriptDownloader(
        httpClient, tempDirectory, maxBytes, requestTimeout, bodyTimeout);
  }

  private void stubResponse(int status, URI responseUri, byte[] body) throws Exception {
    stubResponse(status, responseUri, body, OptionalLong.empty());
  }

  private void stubResponse(int status, URI responseUri, byte[] body, OptionalLong contentLength)
      throws Exception {
    when(httpClient.send(any(HttpRequest.class), anyPathHandler()))
        .thenAnswer(
            invocation -> {
              HttpResponse.BodyHandler<Path> handler = invocation.getArgument(1);
              HttpResponse.BodySubscriber<Path> subscriber =
                  handler.apply(responseInfo(status, contentLength));
              lastSubscription = new TestSubscription();
              subscriber.onSubscribe(lastSubscription);
              if (!lastSubscription.cancelled) {
                subscriber.onNext(List.of(ByteBuffer.wrap(body)));
                subscriber.onComplete();
              }
              Path file = awaitBody(subscriber);
              HttpResponse<Path> response = mock();
              when(response.statusCode()).thenReturn(status);
              when(response.uri()).thenReturn(responseUri);
              when(response.body()).thenReturn(file);
              return response;
            });
  }

  private HttpResponse.ResponseInfo responseInfo(int status, OptionalLong contentLength) {
    HttpResponse.ResponseInfo info = mock(HttpResponse.ResponseInfo.class);
    when(info.statusCode()).thenReturn(status);
    Map<String, List<String>> headers =
        contentLength.isPresent()
            ? Map.of("Content-Length", List.of(Long.toString(contentLength.getAsLong())))
            : Map.of();
    when(info.headers()).thenReturn(HttpHeaders.of(headers, (name, value) -> true));
    return info;
  }

  private HttpResponse.BodyHandler<Path> anyPathHandler() {
    return org.mockito.ArgumentMatchers.any();
  }

  private Path awaitBody(HttpResponse.BodySubscriber<Path> subscriber) throws IOException {
    try {
      return subscriber.getBody().toCompletableFuture().get(2, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      throw new IOException(e.getCause().getMessage(), e.getCause());
    } catch (TimeoutException e) {
      throw new AssertionError("Body subscriber did not terminate", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Test interrupted while waiting for body subscriber", e);
    }
  }

  private Sha256Digest digest(String value) throws Exception {
    return digest(value.getBytes());
  }

  private Sha256Digest digest(byte[] value) throws Exception {
    return new Sha256Digest(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)));
  }

  private void assertTempDirectoryEmpty() throws IOException {
    try (var files = Files.list(tempDirectory)) {
      assertThat(files).isEmpty();
    }
  }

  private static final class TestSubscription implements Flow.Subscription {
    private boolean cancelled;

    @Override
    public void request(long count) {}

    @Override
    public void cancel() {
      cancelled = true;
    }
  }
}
