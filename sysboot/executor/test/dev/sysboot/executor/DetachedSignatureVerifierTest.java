package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DetachedSignatureVerifierTest {

  private static final String ALLOWED_SIGNER = "A".repeat(40);

  @Mock private ShellRunner shellRunner;

  @Test
  void verify_ignoresFakePathAndRunsTrustedAbsoluteGpg() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(
                0,
                "[GNUPG:] VALIDSIG " + ALLOWED_SIGNER + " 0 0 0 4 0 1 10 00\n",
                "",
                Duration.ofMillis(25)));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    verifier.verify(Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(shellRunner).run(captor.capture(), any(), any());
    assertThat(captor.getValue())
        .containsExactly(
            TrustedSystemExecutable.gpg().toString(),
            "--batch",
            "--no-tty",
            "--status-fd=1",
            "--verify",
            "/tmp/artifact.sig",
            "/tmp/artifact.tar.gz");
  }

  @Test
  void verify_whenGpgFails_throwsShellExecutionException() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "BAD signature", Duration.ofMillis(25)));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("BAD signature");
  }

  @Test
  void verify_whenValidSignatureUsesDifferentSigner_rejectsSignature() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(
                0,
                "[GNUPG:] VALIDSIG " + "B".repeat(40) + " 0 0 0 4 0 1 10 00\n",
                "",
                Duration.ZERO));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("allowed signer");
  }

  @Test
  void verify_whenGpgStatusIsMissing_rejectsMalformedResult() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "gpg: Good signature", "", Duration.ZERO));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("missing VALIDSIG");
  }

  @Test
  void verify_whenValidSigStatusIsTruncated_rejectsMalformedResult() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(0, "[GNUPG:] VALIDSIG " + ALLOWED_SIGNER + "\n", "", Duration.ZERO));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("malformed VALIDSIG");
  }

  @Test
  void verify_whenStatusReportsExpiredSignature_rejectsSignature() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(
                0,
                "[GNUPG:] VALIDSIG "
                    + ALLOWED_SIGNER
                    + " 0 0 0 4 0 1 10 00\n[GNUPG:] EXPSIG key user\n",
                "",
                Duration.ZERO));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("invalid status");
  }

  @Test
  void verify_whenValidSignatureUsesSha1_rejectsWeakHashAlgorithm() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(
                0,
                "[GNUPG:] VALIDSIG " + ALLOWED_SIGNER + " 0 0 0 4 0 1 2 00\n",
                "",
                Duration.ZERO));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("unsupported public-key or hash algorithm");
  }

  @ParameterizedTest
  @ValueSource(ints = {1, 3, 19, 22, 27, 28})
  void verify_whenValidSignatureUsesAllowedPublicKeyAlgorithm_acceptsSignature(
      int publicKeyAlgorithm) {
    when(shellRunner.run(any(), any(), any())).thenReturn(validSignature(publicKeyAlgorithm, 10));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    verifier.verify(Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER);
  }

  @ParameterizedTest
  @ValueSource(ints = {8, 9, 10})
  void verify_whenValidSignatureUsesAllowedHashAlgorithm_acceptsSignature(int hashAlgorithm) {
    when(shellRunner.run(any(), any(), any())).thenReturn(validSignature(1, hashAlgorithm));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    verifier.verify(Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER);
  }

  @Test
  void verify_whenValidSignatureUsesDsa_rejectsWeakPublicKeyAlgorithm() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(
                0,
                "[GNUPG:] VALIDSIG " + ALLOWED_SIGNER + " 0 0 0 4 0 17 10 00\n",
                "",
                Duration.ZERO));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("unsupported public-key or hash algorithm");
  }

  @Test
  void verify_whenValidSigAlgorithmsAreMalformed_rejectsStatus() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(
                0,
                "[GNUPG:] VALIDSIG " + ALLOWED_SIGNER + " 0 0 0 4 0 RSA SHA512 00\n",
                "",
                Duration.ZERO));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () ->
                verifier.verify(
                    Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("malformed VALIDSIG");
  }

  @Test
  void verify_whenSigningSubkeyBelongsToAllowedPrimary_acceptsSignature() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(
            new ProcessResult(
                0,
                "[GNUPG:] VALIDSIG "
                    + "B".repeat(40)
                    + " 0 0 0 4 0 22 10 00 "
                    + ALLOWED_SIGNER
                    + "\n",
                "",
                Duration.ZERO));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    verifier.verify(Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"), ALLOWED_SIGNER);
  }

  private ProcessResult validSignature(int publicKeyAlgorithm, int hashAlgorithm) {
    return new ProcessResult(
        0,
        "[GNUPG:] VALIDSIG %s 0 0 0 4 0 %d %d 00%n"
            .formatted(ALLOWED_SIGNER, publicKeyAlgorithm, hashAlgorithm),
        "",
        Duration.ZERO);
  }
}
