package dev.sysboot.executor;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class DetachedSignatureVerifier {

  private static final Duration VERIFY_TIMEOUT = Duration.ofMinutes(1);
  private static final String STATUS_PREFIX = "[GNUPG:] ";
  private static final Set<Integer> ALLOWED_PUBLIC_KEY_ALGORITHMS = Set.of(1, 3, 19, 22, 27, 28);
  private static final Set<Integer> ALLOWED_HASH_ALGORITHMS = Set.of(8, 9, 10);
  private static final Set<String> REJECTED_STATUSES =
      Set.of(
          "BADSIG",
          "ERRSIG",
          "EXPSIG",
          "EXPKEYSIG",
          "REVKEYSIG",
          "KEYEXPIRED",
          "SIGEXPIRED",
          "NODATA",
          "NO_PUBKEY");

  private final ShellRunner shellRunner;

  DetachedSignatureVerifier(ShellRunner shellRunner) {
    this.shellRunner = Objects.requireNonNull(shellRunner);
  }

  void verify(Path signatureFile, Path artifactFile, String allowedSignerFingerprint) {
    var result =
        shellRunner.run(
            List.of(
                TrustedSystemExecutable.gpg().toString(),
                "--batch",
                "--no-tty",
                "--status-fd=1",
                "--verify",
                signatureFile.toString(),
                artifactFile.toString()),
            Map.of(),
            VERIFY_TIMEOUT);
    if (!result.isSuccess()) {
      throw new ShellExecutionException("Detached signature verification failed: " + error(result));
    }
    requireAllowedValidSignature(result.stdout(), allowedSignerFingerprint);
  }

  private void requireAllowedValidSignature(String output, String allowedSignerFingerprint) {
    String allowed = allowedSignerFingerprint.strip().toUpperCase(Locale.ROOT);
    List<String[]> statuses =
        output
            .lines()
            .filter(line -> line.startsWith(STATUS_PREFIX))
            .map(line -> line.substring(STATUS_PREFIX.length()).split("\\s+"))
            .toList();
    if (statuses.stream().anyMatch(this::isRejectedStatus)) {
      throw new ShellExecutionException("Detached signature verification reported invalid status");
    }
    List<String[]> validStatuses =
        statuses.stream().filter(fields -> fields[0].equals("VALIDSIG")).toList();
    if (validStatuses.isEmpty()) {
      throw new ShellExecutionException("Detached signature status is missing VALIDSIG");
    }
    List<ValidSignature> validSignatures =
        validStatuses.stream().map(this::parseValidSignature).toList();
    if (validSignatures.stream().anyMatch(signature -> !signature.usesAllowedAlgorithms())) {
      throw new ShellExecutionException(
          "Detached signature uses an unsupported public-key or hash algorithm");
    }
    if (validSignatures.stream().anyMatch(signature -> !signature.matches(allowed))) {
      throw new ShellExecutionException(
          "Detached signature was not made by the configured allowed signer");
    }
  }

  private boolean isRejectedStatus(String[] fields) {
    return fields.length > 0 && REJECTED_STATUSES.contains(fields[0]);
  }

  private ValidSignature parseValidSignature(String[] fields) {
    if (fields.length != 10 && fields.length != 11) {
      throw malformedStatus();
    }
    String signingFingerprint = requireFingerprint(fields[1]);
    Optional<String> primaryFingerprint =
        fields.length == 11 ? Optional.of(requireFingerprint(fields[10])) : Optional.empty();
    try {
      return new ValidSignature(
          signingFingerprint,
          primaryFingerprint,
          Integer.parseInt(fields[7]),
          Integer.parseInt(fields[8]));
    } catch (NumberFormatException e) {
      throw malformedStatus(e);
    }
  }

  private String requireFingerprint(String value) {
    String fingerprint = value.toUpperCase(Locale.ROOT);
    if (!fingerprint.matches("(?:[0-9A-F]{40}|[0-9A-F]{64})")) {
      throw malformedStatus();
    }
    return fingerprint;
  }

  private ShellExecutionException malformedStatus() {
    return new ShellExecutionException("Detached signature reported malformed VALIDSIG status");
  }

  private ShellExecutionException malformedStatus(Throwable cause) {
    return new ShellExecutionException(
        "Detached signature reported malformed VALIDSIG status", cause);
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

  private record ValidSignature(
      String signingFingerprint,
      Optional<String> primaryFingerprint,
      int publicKeyAlgorithm,
      int hashAlgorithm) {

    boolean usesAllowedAlgorithms() {
      return ALLOWED_PUBLIC_KEY_ALGORITHMS.contains(publicKeyAlgorithm)
          && ALLOWED_HASH_ALGORITHMS.contains(hashAlgorithm);
    }

    boolean matches(String allowed) {
      return allowed.equals(signingFingerprint)
          || primaryFingerprint.filter(allowed::equals).isPresent();
    }
  }
}
