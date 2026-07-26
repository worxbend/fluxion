package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.SudoPasswordProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SudoSessionTest {

  @Test
  void asksForThePasswordOnceNoMatterHowManyPrivilegedCommandsRun() {
    var prompts = new AtomicInteger();
    var validator = new FakeValidator(true);
    try (var session = new SudoSession(counting(prompts, "secret"), validator, longInterval())) {
      for (int i = 0; i < 40; i++) {
        assertThat(session.requestPassword("[sudo] password")).isPresent();
      }
    }

    assertThat(prompts).hasValue(1);
  }

  @Test
  void handsOutCopiesSoACallerZeroingItsArrayCannotBreakTheNextCall() {
    try (var session = new SudoSession(fixed("secret"), new FakeValidator(true), longInterval())) {
      char[] first = session.requestPassword("p").orElseThrow();
      java.util.Arrays.fill(first, '\0');

      assertThat(session.requestPassword("p").orElseThrow())
          .containsExactly("secret".toCharArray());
    }
  }

  @Test
  void retriesWhenSudoRejectsThePassword() {
    var validator = new FakeValidator(false, false, true);
    var supplied = new ArrayList<String>();
    SudoPasswordProvider delegate =
        prompt -> {
          supplied.add(prompt);
          return Optional.of("secret".toCharArray());
        };

    try (var session = new SudoSession(delegate, validator, longInterval())) {
      assertThat(session.requestPassword("[sudo] password")).isPresent();
    }

    assertThat(supplied).hasSize(3);
    assertThat(supplied.get(1)).contains("attempt 2 of 3");
  }

  @Test
  void stopsAskingAfterThreeRejectionsRatherThanLoopingForever() {
    var prompts = new AtomicInteger();
    var validator = new FakeValidator(false, false, false);

    try (var session = new SudoSession(counting(prompts, "wrong"), validator, longInterval())) {
      assertThat(session.requestPassword("p")).isEmpty();
      assertThat(session.requestPassword("p")).isEmpty();
    }

    assertThat(prompts).as("no further prompts after the run is declined").hasValue(3);
  }

  @Test
  void neverPromptsWhenSudoIsPasswordlessOnThisHost() {
    var prompts = new AtomicInteger();
    var validator = new FakeValidator(true);
    validator.passwordless = true;

    try (var session = new SudoSession(counting(prompts, "secret"), validator, longInterval())) {
      assertThat(session.requestPassword("p")).isEmpty();
      assertThat(session.isPasswordless()).isTrue();
      assertThat(session.isAuthenticated()).isTrue();
    }

    assertThat(prompts).hasValue(0);
  }

  @Test
  void aDeclinedPromptIsNotRetried() {
    var prompts = new AtomicInteger();
    SudoPasswordProvider declining =
        prompt -> {
          prompts.incrementAndGet();
          return Optional.empty();
        };

    try (var session = new SudoSession(declining, new FakeValidator(true), longInterval())) {
      assertThat(session.requestPassword("p")).isEmpty();
      assertThat(session.requestPassword("p")).isEmpty();
    }

    assertThat(prompts).hasValue(1);
  }

  @Test
  void closingTheSessionStopsHandingOutThePassword() {
    var session = new SudoSession(fixed("secret"), new FakeValidator(true), longInterval());
    assertThat(session.requestPassword("p")).isPresent();

    session.close();

    assertThat(session.requestPassword("p")).isEmpty();
  }

  @Test
  void theSudoTimestampIsRefreshedWhileTheRunLasts() throws Exception {
    var validator = new FakeValidator(true);
    try (var session = new SudoSession(fixed("secret"), validator, Duration.ofMillis(20))) {
      session.requestPassword("p");
      Thread.sleep(200);
      assertThat(validator.refreshes.get()).isPositive();
    }
  }

  private Duration longInterval() {
    return Duration.ofHours(1);
  }

  private SudoPasswordProvider fixed(String password) {
    return prompt -> Optional.of(password.toCharArray());
  }

  private SudoPasswordProvider counting(AtomicInteger counter, String password) {
    return prompt -> {
      counter.incrementAndGet();
      return Optional.of(password.toCharArray());
    };
  }

  private static final class FakeValidator implements SudoSession.Validator {

    private final List<Boolean> verdicts;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger refreshes = new AtomicInteger();
    private boolean passwordless;

    FakeValidator(Boolean... verdicts) {
      this.verdicts = List.of(verdicts);
    }

    @Override
    public boolean passwordlessSudoAvailable() {
      return passwordless;
    }

    @Override
    public boolean accepts(char[] password) {
      int index = Math.min(calls.getAndIncrement(), verdicts.size() - 1);
      return verdicts.get(index);
    }

    @Override
    public void refresh() {
      refreshes.incrementAndGet();
    }
  }
}
