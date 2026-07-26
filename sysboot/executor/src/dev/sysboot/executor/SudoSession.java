package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.SudoPasswordProvider;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
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
 * than the shell scripts Fluxion is meant to replace. The password is requested once, validated so
 * a typo is caught before any work starts, and then kept warm by refreshing sudo's timestamp in the
 * background for as long as the run lasts.
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
  private boolean promptFree;
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
    if (promptFree) {
      return Optional.empty();
    }
    if (cached != null) {
      return Optional.of(copyOf(cached));
    }
    return switch (validator.availability()) {
      case NOT_PERMITTED -> {
        declined = true;
        log.error("This user may not run sudo on this host; privileged steps will fail.");
        yield Optional.empty();
      }
      case NO_PROMPT_NEEDED -> {
        // `sudo -n -v` also succeeds on a merely *warm* timestamp from an earlier terminal sudo,
        // not only under NOPASSWD. Those are indistinguishable here, so the refresher runs in both
        // cases: it keeps a warm timestamp warm for the whole run instead of letting it lapse
        // mid-flight, and is harmless under NOPASSWD.
        promptFree = true;
        startRefresher();
        log.debug("sudo currently needs no password; keeping the timestamp warm");
        yield Optional.empty();
      }
      case PASSWORD_REQUIRED -> Optional.ofNullable(authenticate(prompt)).map(SudoSession::copyOf);
    };
  }

  private char[] authenticate(String prompt) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      Optional<char[]> supplied = delegate.requestPassword(promptFor(prompt, attempt));
      if (supplied.isEmpty()) {
        declined = true;
        return null;
      }
      char[] candidate = supplied.orElseThrow();
      try {
        switch (validator.check(candidate)) {
          case ACCEPTED -> {
            cached = copyOf(candidate);
            startRefresher();
            return cached;
          }
          case INDETERMINATE -> {
            // Validation timed out or could not run. Treating that as a wrong password would
            // discard a correct one and tell the user it was rejected, so trust it instead and let
            // the first real privileged command be the judge.
            log.warn("Could not verify the sudo password; continuing with it anyway");
            cached = copyOf(candidate);
            startRefresher();
            return cached;
          }
          case REJECTED ->
              log.warn("sudo rejected the password (attempt {} of {})", attempt, MAX_ATTEMPTS);
        }
      } finally {
        Arrays.fill(candidate, '\0');
      }
    }
    declined = true;
    return null;
  }

  private String promptFor(String prompt, int attempt) {
    return attempt == 1 ? prompt : prompt + " (attempt " + attempt + " of " + MAX_ATTEMPTS + ")";
  }

  /** Whether sudo currently runs without prompting, through NOPASSWD or a warm timestamp. */
  public synchronized boolean isPromptFree() {
    return promptFree;
  }

  /** Whether a password has been supplied and accepted, or none is needed. */
  public synchronized boolean isAuthenticated() {
    return promptFree || cached != null;
  }

  private void startRefresher() {
    if (refresher != null) {
      return;
    }
    refresher = Thread.ofVirtual().name("fluxion-sudo-keepalive").start(this::refreshUntilClosed);
  }

  private void refreshUntilClosed() {
    while (!closed.get()) {
      try {
        Thread.sleep(refreshInterval);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      if (closed.get()) {
        return;
      }
      try {
        if (!validator.refresh()) {
          onRefreshFailed();
          return;
        }
      } catch (RuntimeException e) {
        // A keepalive that throws must not surface as a stack trace over the TUI, and must not
        // take the run down with it.
        log.debug("sudo keepalive failed", e);
        onRefreshFailed();
        return;
      }
    }
  }

  /**
   * Stops refreshing and, if we were relying on not being prompted, drops back to prompting.
   *
   * <p>Without this, a host that never caches a timestamp ({@code timestamp_timeout=0}) would fork
   * sudo every sixty seconds for the life of the run and still lose privilege mid-flight.
   */
  private synchronized void onRefreshFailed() {
    if (promptFree && cached == null) {
      promptFree = false;
      log.debug("sudo no longer runs without a password; will prompt on the next privileged step");
    }
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    Thread toInterrupt;
    synchronized (this) {
      toInterrupt = refresher;
      if (cached != null) {
        Arrays.fill(cached, '\0');
        cached = null;
      }
    }
    if (toInterrupt != null) {
      toInterrupt.interrupt();
    }
  }

  private static char[] copyOf(char[] value) {
    return Arrays.copyOf(value, value.length);
  }

  /** How sudo behaves on this host right now. */
  enum Availability {
    /** Runs without a prompt, through NOPASSWD or a still-valid timestamp. */
    NO_PROMPT_NEEDED,
    /** Needs a password. */
    PASSWORD_REQUIRED,
    /** This user may not run sudo at all; prompting would be pointless. */
    NOT_PERMITTED
  }

  /** Verdict on a supplied password. */
  enum AuthResult {
    ACCEPTED,
    REJECTED,
    /** Validation could not complete — a timeout, or sudo could not be run. */
    INDETERMINATE
  }

  /** Talks to the real {@code sudo} binary; separated so tests do not need one. */
  interface Validator {

    Availability availability();

    AuthResult check(char[] password);

    /**
     * @return false when the timestamp could not be refreshed
     */
    boolean refresh();
  }

  static final class SudoCommandValidator implements Validator {

    private static final String NOT_PERMITTED_MARKER = "may not run sudo";

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
    public Availability availability() {
      // -n makes sudo fail rather than prompt, so this never blocks waiting for input.
      ProcessResult result =
          runner.run(List.of("sudo", "-n", "-v"), Optional.empty(), VALIDATE_TIMEOUT);
      if (result.isSuccess()) {
        return Availability.NO_PROMPT_NEEDED;
      }
      return mentionsNotPermitted(result)
          ? Availability.NOT_PERMITTED
          : Availability.PASSWORD_REQUIRED;
    }

    private boolean mentionsNotPermitted(ProcessResult result) {
      return (result.stdout() + result.stderr())
          .toLowerCase(java.util.Locale.ROOT)
          .contains(NOT_PERMITTED_MARKER);
    }

    @Override
    public AuthResult check(char[] password) {
      byte[] stdin = encodeWithNewline(password);
      ProcessResult result =
          runner.run(List.of("sudo", "-S", "-p", "", "-v"), Optional.of(stdin), VALIDATE_TIMEOUT);
      if (result.isSuccess()) {
        return AuthResult.ACCEPTED;
      }
      return result.exitCode() == ProcessExecution.TIMEOUT_EXIT_CODE
          ? AuthResult.INDETERMINATE
          : AuthResult.REJECTED;
    }

    @Override
    public boolean refresh() {
      return runner
          .run(List.of("sudo", "-n", "-v"), Optional.empty(), VALIDATE_TIMEOUT)
          .isSuccess();
    }

    /**
     * Encodes the password without letting an intermediate buffer keep a cleartext copy.
     *
     * <p>{@code Charset.encode} sizes its output from an average bytes-per-char estimate and
     * reallocates when that is too small, orphaning an unzeroed buffer for any non-ASCII password.
     * Allocating for the worst case up front avoids the reallocation entirely.
     */
    private static byte[] encodeWithNewline(char[] password) {
      CharsetEncoder encoder =
          StandardCharsets.UTF_8
              .newEncoder()
              .onMalformedInput(CodingErrorAction.REPLACE)
              .onUnmappableCharacter(CodingErrorAction.REPLACE);
      CharBuffer chars = CharBuffer.wrap(password);
      ByteBuffer encoded =
          ByteBuffer.allocate((int) Math.ceil(password.length * encoder.maxBytesPerChar()) + 1);
      try {
        encoder.encode(chars, encoded, true);
        encoder.flush(encoded);
        encoded.put((byte) '\n');
        byte[] bytes = new byte[encoded.position()];
        encoded.flip();
        encoded.get(bytes);
        return bytes;
      } catch (RuntimeException e) {
        throw new IllegalStateException("Failed to encode the sudo password", e);
      } finally {
        Arrays.fill(encoded.array(), (byte) 0);
      }
    }
  }

  /** Narrow port so the validator can be exercised without spawning processes. */
  @FunctionalInterface
  interface ShellRunnerPort {
    ProcessResult run(List<String> command, Optional<byte[]> stdin, Duration timeout);
  }
}
