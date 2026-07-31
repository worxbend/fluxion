package dev.sysboot.executor;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

final class VerifiedScriptDownloader implements ScriptDownloadClient {

  static final long MAX_SCRIPT_BYTES = 1024L * 1024L;
  static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient httpClient;
  private final Path tempDirectory;
  private final long maxBytes;
  private final Duration requestTimeout;
  private final Duration bodyTimeout;

  VerifiedScriptDownloader() {
    this(
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
        Path.of(System.getProperty("java.io.tmpdir")),
        MAX_SCRIPT_BYTES,
        REQUEST_TIMEOUT);
  }

  VerifiedScriptDownloader(
      HttpClient httpClient, Path tempDirectory, long maxBytes, Duration requestTimeout) {
    this(httpClient, tempDirectory, maxBytes, requestTimeout, requestTimeout);
  }

  VerifiedScriptDownloader(
      HttpClient httpClient,
      Path tempDirectory,
      long maxBytes,
      Duration requestTimeout,
      Duration bodyTimeout) {
    this.httpClient = Objects.requireNonNull(httpClient);
    this.tempDirectory = Objects.requireNonNull(tempDirectory);
    this.maxBytes = requirePositive(maxBytes);
    this.requestTimeout = requirePositive(requestTimeout);
    this.bodyTimeout = requirePositive(bodyTimeout);
  }

  @Override
  public Path download(URI url, Sha256Digest sha256) throws IOException {
    validateUrl(url);
    Path destination = Files.createTempFile(tempDirectory, "fluxion-script-", ".sh");
    boolean verified = false;
    Throwable primaryFailure = null;
    var subscriber = new AtomicReference<BoundedScriptBodySubscriber>();
    try {
      HttpResponse<Path> response = send(url, destination, subscriber);
      requireSuccessful(response);
      verifyDigest(destination, sha256);
      Files.setPosixFilePermissions(destination, PosixFilePermissions.fromString("rwx------"));
      verified = true;
      return destination;
    } catch (IOException | RuntimeException e) {
      primaryFailure = e;
      throw e;
    } finally {
      BoundedScriptBodySubscriber body = subscriber.get();
      if (body != null) {
        FailurePreservingCleanup.run(primaryFailure, body::close);
      }
      if (!verified) {
        FailurePreservingCleanup.run(primaryFailure, () -> Files.deleteIfExists(destination));
      }
    }
  }

  private HttpResponse<Path> send(
      URI url, Path destination, AtomicReference<BoundedScriptBodySubscriber> subscriber)
      throws IOException {
    var request = HttpRequest.newBuilder(url).timeout(requestTimeout).GET().build();
    try {
      return httpClient.send(request, info -> responseSubscriber(info, destination, subscriber));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Script download interrupted", e);
    }
  }

  private HttpResponse.BodySubscriber<Path> responseSubscriber(
      HttpResponse.ResponseInfo info,
      Path destination,
      AtomicReference<BoundedScriptBodySubscriber> subscriber) {
    if (info.statusCode() != 200) {
      return rejected("Script download returned HTTP " + info.statusCode());
    }
    OptionalLong contentLength = contentLength(info.headers());
    if (contentLength.isPresent() && contentLength.getAsLong() > maxBytes) {
      return rejected("Script download exceeds maximum size of " + maxBytes + " bytes");
    }
    try {
      var bounded =
          new BoundedScriptBodySubscriber(destination, maxBytes, contentLength, bodyTimeout);
      subscriber.set(bounded);
      bounded.startDeadline();
      return bounded;
    } catch (IOException e) {
      return rejected("Cannot open temporary script file: " + e.getMessage());
    }
  }

  private OptionalLong contentLength(HttpHeaders headers) {
    try {
      return headers.firstValueAsLong("Content-Length");
    } catch (NumberFormatException e) {
      return OptionalLong.empty();
    }
  }

  private HttpResponse.BodySubscriber<Path> rejected(String message) {
    return new RejectedScriptBodySubscriber(new IOException(message));
  }

  private void requireSuccessful(HttpResponse<?> response) throws IOException {
    validateUrl(response.uri());
    if (response.statusCode() != 200) {
      throw new IOException("Script download returned HTTP " + response.statusCode());
    }
  }

  private void verifyDigest(Path file, Sha256Digest expected) throws IOException {
    String actual = HexFormat.of().formatHex(digest(file));
    if (!actual.equals(expected.value())) {
      throw new IOException("Script SHA-256 mismatch");
    }
  }

  private byte[] digest(Path file) throws IOException {
    try (var input = Files.newInputStream(file)) {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private void validateUrl(URI url) throws IOException {
    if (url == null
        || !"https".equalsIgnoreCase(url.getScheme())
        || url.getHost() == null
        || url.getUserInfo() != null) {
      throw new IOException("Script URL must be HTTPS without user-info");
    }
  }

  private static long requirePositive(long value) {
    if (value <= 0) {
      throw new IllegalArgumentException("Maximum script bytes must be positive");
    }
    return value;
  }

  private static Duration requirePositive(Duration value) {
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException("Request timeout must be positive");
    }
    return value;
  }
}
