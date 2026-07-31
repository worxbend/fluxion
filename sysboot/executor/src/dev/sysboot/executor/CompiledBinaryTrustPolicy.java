package dev.sysboot.executor;

import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import java.util.Optional;

final class CompiledBinaryTrustPolicy {

  Optional<String> failure(CompiledBinaryModule module) {
    Optional<Checksum> directChecksum = module.checksum();
    if (directChecksum.isPresent() && !directChecksum.orElseThrow().hasValidSha256Value()) {
      return Optional.of(
          "Refusing untrusted binary download: checksum must use SHA-256 with a 64-character"
              + " hexadecimal digest");
    }
    boolean hasLiteralChecksum = directChecksum.isPresent();
    boolean hasBoundSignature =
        module.signatureUrl().isPresent() && module.allowedSignerFingerprint().isPresent();
    if (!hasLiteralChecksum && !hasBoundSignature) {
      return Optional.of(
          "Refusing untrusted binary download: configure a literal SHA-256 checksum or a signature"
              + " with an allowed signer fingerprint; checksumUrl is supplemental metadata only");
    }
    if (module.signatureUrl().isPresent() != module.allowedSignerFingerprint().isPresent()) {
      return Optional.of(
          "Refusing detached signature without a matching allowed signer fingerprint");
    }
    return Optional.empty();
  }
}
