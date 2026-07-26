package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.SudoPasswordProvider;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks for the sudo password once per run instead of once per privileged command.
 *
 * <p>A forty-step profile used to produce up to forty password prompts, which is a worse experience
 * than the shell scripts Fluxion is meant to replace. The password is requested once, validated
 * against {@code sudo -S -v} so a typo is caught before any work starts, and then kept warm by
 * refreshing sudo's timestamp in the background for as long as the run lasts.
 *
 * <p>The session is {@link AutoCloseable}: closing it stops the refresher and zeroes the cached
 * password.
 */
public final class SudoSession implements SudoPasswordProvider, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SudoSession.class);

  private static final Duration VALIDATE_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(60);
  private static final int MAX_ATTEMPTS = 3;

  private final SudoPasswordProvider delegate;
  private final Validator validator;
  private final Duration refreshInterval;
  private final AtomicBoolean closed = new AtomicBoolean();

  private char[] cached;
  private boolean passwordless;
  private boolean declined;
  private Thread refresher;

  public SudoSession(SudoPasswordProvider delegate) {
    this(delegate, new SudoCommandValidator(), REFRESH_INTERVAL);
  }

  SudoSession(SudoPasswordProvider delegate, Validator validator, Duration refreshInterval) {
    this.delegate = delegate;
    this.validator = validator;
    this.refreshInterval = refreshInterval;
  }

  @Override
  public synchronized Optional<char[]> requestPassword(String prompt) {
    if (closed.get() || declined) {
      return Optional.empty();
    }
    if (passwordless) {
      return Optional.empty();
    }
    if (cached != null) {
      return Optional.of(copyOf(cached));
    }
    if (validator.passwordlessSudoAvailable()) {
      passwordless = true;
      log.debug("sudo does not require a password on this host");
      return Optional.empty();
    }
    return Optional.ofNullable(authenticate(prompt)).map(SudoSession::copyOf);
  }

  private char[] authenticate(String prompt) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      Optional<char[]> supplied = delegate.requestPassword(promptFor(prompt, attempt));
      if (supplied.isEmpty()) {
        declined = true;
        return null;
      }
      char[] candidate = supplied.orElseThrow();
      if (validator.accepts(candidate)) {
        cached = copyOf(candidate);
        Arrays.fill(candidate, '\0');
        startRefresher();
        return cached;
      }
      Arrays.fill(candidate, '\0');
      log.warn("sudo rejected the password (attempt {} of {})", attempt, MAX_ATTEMPTS);
    }
    declined = true;
    return null;
  }

  private String promptFor(String prompt, int attempt) {
    return attempt == 1 ? prompt : prompt + " (attempt " + attempt + " of " + MAX_ATTEMPTS + ")";
  }

  /** Whether this host lets sudo run without a password. */
  public synchronized boolean isPasswordless() {
    return passwordless;
  }

  /** Whether a password has been supplied and accepted. */
  public synchronized boolean isAuthenticated() {
    return passwordless || cached != null;
  }

  private void startRefresher() {
    refresher =
        Thread.ofVirtual()
            .name("fluxion-sudo-keepalive")
            .start(
                () -> {
                  while (!closed.get()) {
                    try {
                      Thread.sleep(refreshInterval);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      return;
                    }
                    if (!closed.get()) {
                      validator.refresh();
                    }
                  }
                });
  }

  @Override
  public synchronized void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    if (refresher != null) {
      refresher.interrupt();
    }
    if (cached != null) {
      Arrays.fill(cached, '\0');
      cached = null;
    }
  }

  private static char[] copyOf(char[] value) {
    return Arrays.copyOf(value, value.length);
  }

  /** Talks to the real {@code sudo} binary; separated so tests do not need one. */
  interface Validator {

    boolean passwordlessSudoAvailable();

    boolean accepts(char[] password);

    void refresh();
  }

  static final class SudoCommandValidator implements Validator {

    private final ShellRunnerPort runner;

    SudoCommandValidator() {
      this(
          (command, stdin, timeout) ->
              ProcessExecution.run(
                  stdin
                      .map(
                          bytes ->
                              ProcessExecution.Request.of(command, Map.of(), timeout)
                                  .withStdin(bytes))
                      .orElseGet(() -> ProcessExecution.Request.of(command, Map.of(), timeout))));
    }

    SudoCommandValidator(ShellRunnerPort runner) {
      this.runner = runner;
    }

    @Override
    public boolean passwordlessSudoAvailable() {
      // -n makes sudo fail rather than prompt, so this never blocks waiting for input.
      return runner
          .run(List.of("sudo", "-n", "-v"), Optional.empty(), VALIDATE_TIMEOUT)
          .isSuccess();
    }

    @Override
    public boolean accepts(char[] password) {
      byte[] stdin = encodeWithNewline(password);
      return runner
          .run(List.of("sudo", "-S", "-p", "", "-v"), Optional.of(stdin), VALIDATE_TIMEOUT)
          .isSuccess();
    }

    @Override
    public void refresh() {
      runner.run(List.of("sudo", "-n", "-v"), Optional.empty(), VALIDATE_TIMEOUT);
    }

    private static byte[] encodeWithNewline(char[] password) {
      ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
      try {
        byte[] bytes = new byte[encoded.remaining() + 1];
        encoded.get(bytes, 0, bytes.length - 1);
        bytes[bytes.length - 1] = '\n';
        return bytes;
      } finally {
        if (encoded.hasArray()) {
          Arrays.fill(encoded.array(), (byte) 0);
        }
      }
    }
  }

  /** Narrow port so the validator can be exercised without spawning processes. */
  @FunctionalInterface
  interface ShellRunnerPort {
    ProcessResult run(List<String> command, Optional<byte[]> stdin, Duration timeout);
  }
}
