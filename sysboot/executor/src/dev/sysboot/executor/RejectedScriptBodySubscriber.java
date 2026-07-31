package dev.sysboot.executor;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

final class RejectedScriptBodySubscriber implements HttpResponse.BodySubscriber<Path> {

  private final CompletableFuture<Path> body;

  RejectedScriptBodySubscriber(IOException failure) {
    body = CompletableFuture.failedFuture(failure);
  }

  @Override
  public CompletionStage<Path> getBody() {
    return body;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.cancel();
  }

  @Override
  public void onNext(List<ByteBuffer> buffers) {}

  @Override
  public void onError(Throwable throwable) {}

  @Override
  public void onComplete() {}
}
