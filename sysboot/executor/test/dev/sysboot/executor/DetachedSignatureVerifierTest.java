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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DetachedSignatureVerifierTest {

  @Mock private ShellRunner shellRunner;

  @Test
  void verify_runsGpgBatchVerifyWithSignatureAndArtifact() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(25)));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    verifier.verify(Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
    verify(shellRunner).run(captor.capture(), any(), any());
    assertThat(captor.getValue())
        .containsExactly("gpg", "--batch", "--verify", "/tmp/artifact.sig", "/tmp/artifact.tar.gz");
  }

  @Test
  void verify_whenGpgFails_throwsShellExecutionException() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "BAD signature", Duration.ofMillis(25)));
    var verifier = new DetachedSignatureVerifier(shellRunner);

    assertThatThrownBy(
            () -> verifier.verify(Path.of("/tmp/artifact.sig"), Path.of("/tmp/artifact.tar.gz")))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("BAD signature");
  }
}
