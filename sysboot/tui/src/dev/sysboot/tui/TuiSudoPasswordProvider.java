package dev.sysboot.tui;

import dev.sysboot.core.SudoPasswordProvider;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

public final class TuiSudoPasswordProvider implements SudoPasswordProvider {

  private static final int TIMEOUT_SECONDS = 120;

  private final SynchronousQueue<char[]> passwordQueue = new SynchronousQueue<>();
  private volatile String pendingPrompt;

  @Override
  public Optional<char[]> requestPassword(String prompt) {
    var console = System.console();
    if (console != null) {
      return Optional.ofNullable(console.readPassword("%s ", prompt));
    }
    this.pendingPrompt = prompt;
    try {
      char[] password = passwordQueue.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return Optional.ofNullable(password).filter(p -> p.length > 0);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    } finally {
      this.pendingPrompt = null;
    }
  }

  public boolean isWaitingForPassword() {
    return pendingPrompt != null;
  }

  public Optional<String> pendingPrompt() {
    return Optional.ofNullable(pendingPrompt);
  }

  public void submitPassword(char[] password) {
    char[] copy = Arrays.copyOf(password, password.length);
    try {
      passwordQueue.offer(copy, 5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Arrays.fill(copy, '\0');
    }
  }

  public void cancel() {
    try {
      passwordQueue.offer(new char[0], 1, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
