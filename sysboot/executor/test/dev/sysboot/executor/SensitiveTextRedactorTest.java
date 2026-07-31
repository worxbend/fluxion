package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ShellEnvironmentVariable;
import java.util.List;
import org.junit.jupiter.api.Test;

class SensitiveTextRedactorTest {

  private final SensitiveTextRedactor redactor = new SensitiveTextRedactor();

  @Test
  void redact_whenSensitiveEnvironmentValueAppearsStandalone_masksValue() {
    var environment =
        List.of(
            new ShellEnvironmentVariable("API_KEY", "api-value", true),
            new ShellEnvironmentVariable("MONKEY", "banana", false));

    String result = redactor.redact("output api-value banana", environment);

    assertThat(result).contains("<redacted>", "banana").doesNotContain("api-value");
  }

  @Test
  void redactCommand_whenValueAppearsInArgv_masksKnownAndOptionValues() {
    var environment = List.of(new ShellEnvironmentVariable("SSH_PRIVATE_KEY", "key-value", true));

    List<String> result =
        redactor.redactCommand(
            List.of("tool", "--private-key", "key-value", "--api-key", "api-value"), environment);

    assertThat(result)
        .containsExactly("tool", "--private-key", "<redacted>", "--api-key", "<redacted>");
  }

  @Test
  void redact_whenControlsSplitSensitiveValue_normalizesBeforeExactReplacement() {
    var environment = List.of(new ShellEnvironmentVariable("API_KEY", "hunter2", true));

    String result = redactor.redact("value=hun\u001B[31mter2", environment);

    assertThat(result).isEqualTo("value=<redacted>");
  }

  @Test
  void redact_whenSensitiveValueIsTriviallyShort_masksWholeContainingLine() {
    var environment = List.of(new ShellEnvironmentVariable("TOKEN", "1", true));

    String result = redactor.redact("value is 1", environment);

    assertThat(result).isEqualTo("<redacted>");
  }

  @Test
  void redact_whenNoShortSecretIsRegistered_preservesOrdinaryNumericOutput() {
    String result = redactor.redact("progress 10/11", List.of());

    assertThat(result).isEqualTo("progress 10/11");
  }

  @Test
  void streaming_whenExactSecretIsSplitAcrossChunks_neverEmitsSecretPrefix() {
    var environment = List.of(new ShellEnvironmentVariable("API_KEY", "hunter2", true));
    var streaming = redactor.streaming(environment);

    assertThat(streaming.redact("value=hun")).isEqualTo("value=");
    assertThat(streaming.redact("ter2")).isEqualTo("<redacted>");
    assertThat(streaming.finish()).isEmpty();
  }

  @Test
  void streaming_whenOrdinaryTextEndsLikeSecretPrefix_flushesWithoutLoss() {
    var environment = List.of(new ShellEnvironmentVariable("API_KEY", "hunter2", true));
    var streaming = redactor.streaming(environment);

    String prompt = streaming.redact("ordinary hun");
    String trailing = streaming.finish();

    assertThat(prompt + trailing).isEqualTo("ordinary hun");
  }
}
