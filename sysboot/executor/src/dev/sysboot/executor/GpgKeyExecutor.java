package dev.sysboot.executor;

import dev.sysboot.core.GpgKeyModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Imports repository signing keys.
 *
 * <p>Importing a key decides what the machine will trust to install software as root, so a declared
 * fingerprint is verified against the key that actually arrived, and a mismatch removes the key
 * rather than leaving it installed.
 */
public final class GpgKeyExecutor {

  private static final Duration TIMEOUT = Duration.ofMinutes(2);

  private final ShellRunner shellRunner;

  public GpgKeyExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(GpgKeyModule module) {
    var failures = new ArrayList<String>();
    for (GpgKeyModule.GpgKey key : module.keys()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      importKey(key).ifPresent(failures::add);
    }
    if (!failures.isEmpty() && !module.continueOnError()) {
      return new StepResult.Failure(
          module.name().value(), String.join("; ", failures), 1, Duration.ZERO);
    }
    return new StepResult.Success(module.name().value(), Duration.ZERO);
  }

  /** True when a keyring-backed key is already present. RPM imports are always re-run (cheap). */
  public boolean alreadyImported(GpgKeyModule.GpgKey key) {
    return key.keyring().filter(Files::isReadable).isPresent();
  }

  public List<String> commandPreview(GpgKeyModule module) {
    var preview = new ArrayList<String>();
    module.keys().forEach(key -> preview.addAll(importCommand(key)));
    return List.copyOf(preview);
  }

  private Optional<String> importKey(GpgKeyModule.GpgKey key) {
    if (alreadyImported(key)) {
      return Optional.empty();
    }
    ProcessResult result = shellRunner.run(importCommand(key), Map.of(), TIMEOUT);
    if (!result.isSuccess()) {
      return Optional.of("import " + key.url() + ": " + detail(result));
    }
    return verifyFingerprint(key);
  }

  private Optional<String> verifyFingerprint(GpgKeyModule.GpgKey key) {
    Optional<String> expected = key.fingerprint().map(this::normalize);
    if (expected.isEmpty() || key.keyring().isEmpty()) {
      return Optional.empty();
    }
    ProcessResult result =
        shellRunner.run(
            List.of("gpg", "--show-keys", "--with-colons", key.keyring().orElseThrow().toString()),
            Map.of(),
            TIMEOUT);
    boolean matches =
        result
            .stdout()
            .lines()
            .filter(line -> line.startsWith("fpr:"))
            .anyMatch(line -> normalize(line).contains(expected.orElseThrow()));
    if (matches) {
      return Optional.empty();
    }
    // Leaving a key the profile did not vouch for is worse than failing the step.
    removeKeyring(key);
    return Optional.of("fingerprint mismatch for " + key.url() + "; the imported key was removed");
  }

  private void removeKeyring(GpgKeyModule.GpgKey key) {
    key.keyring()
        .ifPresent(
            path ->
                shellRunner.run(List.of("sudo", "rm", "-f", path.toString()), Map.of(), TIMEOUT));
  }

  private List<String> importCommand(GpgKeyModule.GpgKey key) {
    return key.keyring()
        .map(
            keyring ->
                List.of(
                    "/bin/sh",
                    "-c",
                    "curl -fsSL "
                        + shellQuote(key.url())
                        + " | sudo gpg --dearmor --yes -o "
                        + shellQuote(keyring.toString())))
        .orElseGet(() -> List.of("sudo", "rpm", "--import", key.url()));
  }

  private String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'";
  }

  private String normalize(String value) {
    return value.replace(" ", "").replace(":", "").toUpperCase(Locale.ROOT);
  }

  private String detail(ProcessResult result) {
    String text = result.stderr().isBlank() ? result.stdout() : result.stderr();
    return text.isBlank() ? "exit " + result.exitCode() : text.strip();
  }
}
