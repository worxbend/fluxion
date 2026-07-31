package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DisplayTextSanitizerTest {

  @Test
  void sanitize_whenTerminalSequencesAndControlsPresent_removesTheirEffect() {
    String hostile =
        "safe\u001B[31mred\u001B[0m "
            + "\u001B]8;;https://evil.test\u0007click\u001B]8;;\u0007 "
            + "api_key=seX\bcret\u0000\r\nforged";

    String result = new DisplayTextSanitizer().sanitizeLine(hostile);

    assertThat(result)
        .contains("safered", "click", "api_key=<redacted>")
        .doesNotContain("\u001B", "\u0007", "\u0000", "\r", "\n", "https://evil.test", "secret");
  }

  @Test
  void sanitize_whenTextIsExplicitlyMultiline_preservesOnlyNewlineSeparators() {
    String result = new DisplayTextSanitizer().sanitize("first\r\nsecond\tline");

    assertThat(result).isEqualTo("first \nsecond line");
  }

  @Test
  void sanitizeLine_whenBidiFormatAndUnicodeSeparatorsPresent_removesSpoofingControls() {
    String result =
        new DisplayTextSanitizer()
            .sanitizeLine("left\u202Eright\u2066isolated\u2069\u2028forged\u2029entry");

    assertThat(result)
        .isEqualTo("leftrightisolated forged entry")
        .doesNotContain("\u202E", "\u2066", "\u2069", "\u2028", "\u2029");
  }

  @Test
  void streamingLines_whenPrivateKeySpansLines_masksWholeBlockWithinScope() {
    var lines = new DisplayTextSanitizer().streamingLines();

    assertThat(lines.sanitizeLine("-----BEGIN OPENSSH PRIVATE KEY-----")).isEqualTo("<redacted>");
    assertThat(lines.sanitizeLine("c2VjcmV0LWtleS1tYXRlcmlhbA==")).isEqualTo("<redacted>");
    assertThat(lines.sanitizeLine("-----END OPENSSH PRIVATE KEY-----")).isEqualTo("<redacted>");
    assertThat(lines.sanitizeLine("ordinary output")).isEqualTo("ordinary output");
  }

  @Test
  void sanitize_whenPrivateKeyHasNoEndMarker_masksToEndOfValue() {
    String incomplete =
        "failure:\n-----BEGIN OPENSSH PRIVATE KEY-----\nc2VjcmV0LWtleS1tYXRlcmlhbA==";

    assertThat(new DisplayTextSanitizer().sanitize(incomplete))
        .isEqualTo("failure:\n<redacted>")
        .doesNotContain("c2VjcmV0LWtleS1tYXRlcmlhbA==");
    assertThat(new DisplayTextSanitizer().sanitizeLine(incomplete))
        .isEqualTo("failure: <redacted>")
        .doesNotContain("c2VjcmV0LWtleS1tYXRlcmlhbA==");
  }

  @Test
  void streamingLines_whenPrivateKeyMarkerIsSplitAcrossChunks_neverEmitsMarkerOrBody() {
    var lines = new DisplayTextSanitizer().streamingLines();

    assertThat(lines.sanitizeLine("prefix -----BEGIN OPEN")).isEqualTo("prefix ");
    assertThat(lines.sanitizeLine("SSH PRIVATE KEY-----")).isEqualTo("<redacted>");
    assertThat(lines.sanitizeLine("c2VjcmV0LWtleS1tYXRlcmlhbA==")).isEqualTo("<redacted>");
    assertThat(lines.sanitizeLine("-----END OPENSSH PRIVATE KEY-----")).isEqualTo("<redacted>");
    assertThat(lines.finish()).isEmpty();
  }

  @Test
  void streamingLines_whenOrdinarySuffixResemblesMarker_flushesWithoutLoss() {
    var lines = new DisplayTextSanitizer().streamingLines();

    String prompt = lines.sanitizeLine("ordinary-");
    String trailing = lines.finish();

    assertThat(prompt + trailing).isEqualTo("ordinary-");
  }

  @Test
  void streamingLines_whenPossiblePemMarkerExceedsCarryLimit_failsClosed() {
    var lines = new DisplayTextSanitizer().streamingLines();

    String result = lines.sanitizeLine("prefix -----BEGIN " + "A".repeat(200));

    assertThat(result).isEqualTo("prefix <redacted>");
    assertThat(lines.sanitizeLine("key-material")).isEqualTo("<redacted>");
  }
}
