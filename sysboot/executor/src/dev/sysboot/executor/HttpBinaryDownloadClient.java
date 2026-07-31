package dev.sysboot.executor;

import dev.sysboot.core.Sha256Digest;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class HttpBinaryDownloadClient implements BinaryDownloadClient {

  private static final Duration FILE_TIMEOUT = Duration.ofMinutes(10);
  private static final Duration TEXT_TIMEOUT = Duration.ofMinutes(1);
  static final long MAX_FILE_BYTES = 1024L * 1024L * 1024L;
  static final long MAX_TEXT_BYTES = 1024L * 1024L;
  private static final int BUFFER_BYTES = 64 * 1024;

  private final HttpClient httpClient;
  private final long maxFileBytes;
  private final long maxTextBytes;
  private final Duration fileTimeout;
  private final Duration textTimeout;

  HttpBinaryDownloadClient() {
    this(defaultHttpClient());
  }

  HttpBinaryDownloadClient(long maxFileBytes) {
    this(defaultHttpClient(), maxFileBytes, MAX_TEXT_BYTES);
  }

  HttpBinaryDownloadClient(HttpClient httpClient) {
    this(httpClient, MAX_FILE_BYTES, MAX_TEXT_BYTES);
  }

  HttpBinaryDownloadClient(HttpClient httpClient, long maxFileBytes, long maxTextBytes) {
    this(httpClient, maxFileBytes, maxTextBytes, FILE_TIMEOUT, TEXT_TIMEOUT);
  }

  HttpBinaryDownloadClient(
      HttpClient httpClient,
      long maxFileBytes,
      long maxTextBytes,
      Duration fileTimeout,
      Duration textTimeout) {
    this.httpClient = httpClient;
    this.maxFileBytes = requirePositive(maxFileBytes, "Maximum file bytes");
    this.maxTextBytes = requirePositive(maxTextBytes, "Maximum text bytes");
    this.fileTimeout = requirePositive(fileTimeout, "File timeout");
    this.textTimeout = requirePositive(textTimeout, "Text timeout");
  }

  @Override
  public void downloadToFile(URI url, Path destination) throws IOException {
    downloadToFileWithDigest(url, destination);
  }

  @Override
  public Sha256Digest downloadToFileWithDigest(URI url, Path destination) throws IOException {
    validateUrl(url);
    var request = HttpRequest.newBuilder(url).timeout(fileTimeout).GET().build();
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      return writeFileResponse(response, url, destination);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      deletePartial(destination, e);
      throw new IOException("Download interrupted", e);
    } catch (IOException e) {
      deletePartial(destination, e);
      throw e;
    } catch (RuntimeException e) {
      deletePartial(destination, e);
      throw new IOException("Download failed for " + url, e);
    }
  }

  private Sha256Digest writeFileResponse(
      HttpResponse<InputStream> response, URI url, Path destination) throws IOException {
    InputStream input = response.body();
    boolean closeScheduled = false;
    Throwable primaryFailure = null;
    try {
      requireOk(response.statusCode(), "Download failed", url);
      validateUrl(response.uri());
      requireContentLength(response, maxFileBytes);
      MessageDigest digest = sha256();
      try (OutputStream output = new DigestOutputStream(openDestination(destination), digest)) {
        long copied = copyWithDeadline(input, output, maxFileBytes, fileTimeout);
        requireComplete(response, copied);
      }
      return new Sha256Digest(HexFormat.of().formatHex(digest.digest()));
    } catch (BodyTransferTerminatedException e) {
      closeScheduled = true;
      primaryFailure = e;
      throw e;
    } catch (IOException | RuntimeException e) {
      primaryFailure = e;
      throw e;
    } finally {
      if (!closeScheduled) {
        FailurePreservingCleanup.run(primaryFailure, input::close);
      }
    }
  }

  private MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private OutputStream openDestination(Path destination) throws IOException {
    return Files.newOutputStream(
        destination,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE);
  }

  @Override
  public String downloadText(URI url) throws IOException {
    validateUrl(url);
    var request = HttpRequest.newBuilder(url).timeout(textTimeout).GET().build();
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
      return readTextResponse(response, url);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Checksum download interrupted", e);
    } catch (RuntimeException e) {
      throw new IOException("Checksum download failed for " + url, e);
    }
  }

  private String readTextResponse(HttpResponse<InputStream> response, URI url) throws IOException {
    InputStream input = response.body();
    boolean closeScheduled = false;
    Throwable primaryFailure = null;
    try (var output = new java.io.ByteArrayOutputStream()) {
      requireOk(response.statusCode(), "Checksum download failed", url);
      validateUrl(response.uri());
      requireContentLength(response, maxTextBytes);
      long copied = copyWithDeadline(input, output, maxTextBytes, textTimeout);
      requireComplete(response, copied);
      return output.toString(StandardCharsets.UTF_8);
    } catch (BodyTransferTerminatedException e) {
      closeScheduled = true;
      primaryFailure = e;
      throw e;
    } catch (IOException | RuntimeException e) {
      primaryFailure = e;
      throw e;
    } finally {
      if (!closeScheduled) {
        FailurePreservingCleanup.run(primaryFailure, input::close);
      }
    }
  }

  private long copyWithDeadline(
      InputStream input, OutputStream output, long maxBytes, Duration timeout) throws IOException {
    var executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    Future<Long> copy = executor.submit(() -> copyBounded(input, output, maxBytes));
    try {
      return copy.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      cancelAndClose(copy, input, e);
      throw new BodyTransferTerminatedException("Download body timed out after " + timeout, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      cancelAndClose(copy, input, e);
      throw new BodyTransferTerminatedException("Download body interrupted", e);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof IOException ioException) {
        throw new IOException(ioException.getMessage(), e);
      }
      throw new IOException("Download body failed", e);
    } finally {
      executor.shutdownNow();
    }
  }

  private void cancelAndClose(Future<Long> copy, InputStream input, Throwable failure) {
    copy.cancel(true);
    try {
      Thread.ofVirtual()
          .name("sysboot-download-close")
          .start(
              () -> {
                try {
                  input.close();
                } catch (IOException | RuntimeException ignored) {
                  // The original transfer failure is authoritative.
                }
              });
    } catch (RuntimeException schedulingFailure) {
      failure.addSuppressed(schedulingFailure);
    }
  }

  private long copyBounded(InputStream input, OutputStream output, long maxBytes)
      throws IOException {
    byte[] buffer = new byte[BUFFER_BYTES];
    long copied = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (read == 0) {
        continue;
      }
      copied += read;
      if (copied > maxBytes) {
        throw new IOException("Download exceeds maximum size of " + maxBytes + " bytes");
      }
      output.write(buffer, 0, read);
    }
    return copied;
  }

  private void requireContentLength(HttpResponse<?> response, long maxBytes) throws IOException {
    OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
    if (contentLength.isPresent() && contentLength.orElseThrow() > maxBytes) {
      throw new IOException("Download exceeds maximum size of " + maxBytes + " bytes");
    }
  }

  private void requireComplete(HttpResponse<?> response, long copied) throws IOException {
    OptionalLong contentLength = response.headers().firstValueAsLong("Content-Length");
    if (contentLength.isPresent() && copied != contentLength.orElseThrow()) {
      throw new IOException(
          "Download was truncated: expected "
              + contentLength.orElseThrow()
              + " bytes but received "
              + copied);
    }
  }

  private void validateUrl(URI url) throws IOException {
    if (url == null
        || !"https".equalsIgnoreCase(url.getScheme())
        || url.getHost() == null
        || url.getUserInfo() != null) {
      throw new IOException("Download URL must be HTTPS without user-info: " + url);
    }
  }

  private void deletePartial(Path destination, Throwable failure) {
    try {
      Files.deleteIfExists(destination);
    } catch (IOException | SecurityException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private static long requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static HttpClient defaultHttpClient() {
    return HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
  }

  private static Duration requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private void requireOk(int statusCode, String message, URI url) throws IOException {
    if (statusCode != 200) {
      throw new IOException(message + " with HTTP " + statusCode + " for " + url);
    }
  }

  private static final class BodyTransferTerminatedException extends IOException {

    private static final long serialVersionUID = 1L;

    private BodyTransferTerminatedException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
