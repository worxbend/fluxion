package dev.sysboot.tui;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TuiSudoPasswordProviderTest {

  @Test
  void requestPassword_whenReaderUnavailable_failsPromptly() {
    var provider = new TuiSudoPasswordProvider(new UnavailableReader());

    org.junit.jupiter.api.Assertions.assertTimeout(
        Duration.ofMillis(100), () -> assertThat(provider.requestPassword("password")).isEmpty());
    assertThat(provider.isWaitingForPassword()).isFalse();
  }

  @Test
  void requestPassword_whileConsoleReadBlocks_exposesPendingPromptAndCopiesPassword()
      throws Exception {
    char[] supplied = "secret".toCharArray();
    var reader = new BlockingReader(supplied);
    var provider = new TuiSudoPasswordProvider(reader);
    var result = new AtomicReference<Optional<char[]>>();
    Thread requester =
        Thread.ofVirtual().start(() -> result.set(provider.requestPassword("sudo required")));

    reader.entered.await();
    assertThat(provider.pendingPrompt()).contains("sudo required");
    reader.release.countDown();
    requester.join();

    char[] returned = result.get().orElseThrow();
    assertThat(returned).containsExactly("secret".toCharArray());
    assertThat(supplied).containsOnly('\0');
    assertThat(provider.pendingPrompt()).isEmpty();
    Arrays.fill(returned, '\0');
  }

  private static final class UnavailableReader implements TuiSudoPasswordProvider.PasswordReader {

    @Override
    public boolean isAvailable() {
      return false;
    }

    @Override
    public char[] readPassword(String prompt) {
      throw new AssertionError("Unavailable reader must not be called");
    }
  }

  private static final class BlockingReader implements TuiSudoPasswordProvider.PasswordReader {

    private final char[] password;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    private BlockingReader(char[] password) {
      this.password = password;
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public char[] readPassword(String prompt) {
      entered.countDown();
      try {
        release.await();
        return password;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
      }
    }
  }
}
