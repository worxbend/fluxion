package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DetachedSignatureVerifier {

  private static final Duration VERIFY_TIMEOUT = Duration.ofMinutes(1);

  private final ShellRunner shellRunner;

  DetachedSignatureVerifier(ShellRunner shellRunner) {
    this.shellRunner = Objects.requireNonNull(shellRunner);
  }

  void verify(Path signatureFile, Path artifactFile) {
    var result =
        shellRunner.run(
            List.of(
                "gpg", "--batch", "--verify", signatureFile.toString(), artifactFile.toString()),
            Map.of(),
            VERIFY_TIMEOUT);
    if (!result.isSuccess()) {
      throw new ShellExecutionException("Detached signature verification failed: " + error(result));
    }
  }

  private String error(ProcessResult result) {
    if (!result.stderr().isBlank()) {
      return result.stderr();
    }
    if (!result.stdout().isBlank()) {
      return result.stdout();
    }
    return "gpg exited with code " + result.exitCode();
  }
}
