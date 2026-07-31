package dev.sysboot.executor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class BoundedScriptBodySubscriber implements HttpResponse.BodySubscriber<Path> {

  private final Path destination;
  private final long maxBytes;
  private final OptionalLong expectedBytes;
  private final Duration bodyTimeout;
  private final OutputStream output;
  private final CompletableFuture<Path> body = new CompletableFuture<>();
  private final ScheduledExecutorService deadlineExecutor =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofVirtual().name("fluxion-script-deadline").factory());
  private Flow.Subscription subscription;
  private ScheduledFuture<?> deadline;
  private long received;
  private boolean closed;

  BoundedScriptBodySubscriber(
      Path destination, long maxBytes, OptionalLong expectedBytes, Duration bodyTimeout)
      throws IOException {
    this.destination = destination;
    this.maxBytes = maxBytes;
    this.expectedBytes = expectedBytes;
    this.bodyTimeout = bodyTimeout;
    this.output =
        Files.newOutputStream(
            destination, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
  }

  synchronized void startDeadline() {
    deadline =
        deadlineExecutor.schedule(this::onTimeout, bodyTimeout.toNanos(), TimeUnit.NANOSECONDS);
  }

  @Override
  public CompletionStage<Path> getBody() {
    return body;
  }

  @Override
  public synchronized void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    if (body.isDone()) {
      subscription.cancel();
      return;
    }
    subscription.request(1);
  }

  @Override
  public synchronized void onNext(List<ByteBuffer> buffers) {
    try {
      for (ByteBuffer buffer : buffers) {
        write(buffer);
      }
      subscription.request(1);
    } catch (IOException e) {
      subscription.cancel();
      close();
      body.completeExceptionally(e);
    }
  }

  @Override
  public synchronized void onError(Throwable throwable) {
    close();
    body.completeExceptionally(throwable);
  }

  @Override
  public synchronized void onComplete() {
    try {
      requireExpectedLength();
      output.close();
      closed = true;
      body.complete(destination);
    } catch (IOException e) {
      closeOutput();
      body.completeExceptionally(e);
    } finally {
      cancelDeadline();
    }
  }

  synchronized void close() {
    if (subscription != null && !body.isDone()) {
      subscription.cancel();
    }
    closeOutput();
    cancelDeadline();
  }

  private void write(ByteBuffer buffer) throws IOException {
    int size = buffer.remaining();
    if (received + size > maxBytes) {
      throw new IOException("Script download exceeds maximum size of " + maxBytes + " bytes");
    }
    byte[] bytes = new byte[size];
    buffer.get(bytes);
    output.write(bytes);
    received += size;
  }

  private void requireExpectedLength() throws IOException {
    if (expectedBytes.isPresent() && expectedBytes.getAsLong() != received) {
      throw new IOException(
          "Script download was truncated or exceeded its declared Content-Length: expected "
              + expectedBytes.getAsLong()
              + " bytes but received "
              + received);
    }
  }

  private synchronized void onTimeout() {
    if (body.isDone()) {
      return;
    }
    if (subscription != null) {
      subscription.cancel();
    }
    closeOutput();
    body.completeExceptionally(
        new IOException("Script response body timed out after " + bodyTimeout));
    cancelDeadline();
  }

  private void closeOutput() {
    if (closed) {
      return;
    }
    try {
      output.close();
    } catch (IOException ignored) {
      // The original transfer or verification failure is more useful.
    } finally {
      closed = true;
    }
  }

  private void cancelDeadline() {
    if (deadline != null) {
      deadline.cancel(true);
    }
    deadlineExecutor.shutdownNow();
  }
}
