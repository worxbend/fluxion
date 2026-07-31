package dev.sysboot.executor;

import dev.sysboot.core.PrivilegePreflight;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

public final class SudoPrivilegePreflight {

  private static final Duration TIMEOUT = Duration.ofSeconds(30);
  private static final String PROMPT = "Authenticate sudo before applying this profile";

  private SudoPrivilegePreflight() {}

  public static PrivilegePreflight interactive(SudoSession session) {
    return () -> authenticate(session);
  }

  public static PrivilegePreflight nonInteractive(ShellRunner runner) {
    return () -> validateWithoutPrompt(runner);
  }

  private static void authenticate(SudoSession session) {
    Optional<char[]> supplied = session.requestPassword(PROMPT);
    supplied.ifPresent(password -> Arrays.fill(password, '\0'));
    if (!session.isAuthenticated()) {
      throw new ShellExecutionException(
          "This profile requires sudo, but interactive authentication was not completed");
    }
  }

  private static void validateWithoutPrompt(ShellRunner runner) {
    ProcessResult result = runner.run(SudoCommand.validateWithoutPrompt(), Map.of(), TIMEOUT);
    if (!result.isSuccess()) {
      throw new ShellExecutionException(
          "This profile requires sudo, but non-interactive sudo validation failed; "
              + "authenticate sudo first or use the TUI");
    }
  }
}
