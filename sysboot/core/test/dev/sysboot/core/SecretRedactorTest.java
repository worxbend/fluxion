package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SecretRedactorTest {

  private final SecretRedactor redactor = new SecretRedactor();

  @ParameterizedTest
  @ValueSource(
      strings = {
        "API_KEY",
        "apiKey",
        "ACCESS_KEY_ID",
        "access-key",
        "SSH_PRIVATE_KEY",
        "privateKey",
        "KEY_PASSPHRASE",
        "keyPassphrase",
        "AUTHORIZATION",
        "AUTH_TOKEN",
        "DB_PASSWORD",
        "CLIENT_CREDENTIAL",
        "CLIENT_CREDENTIALS"
      })
  void isSensitiveName_whenNameIdentifiesCredential_returnsTrue(String name) {
    assertThat(SecretRedactor.isSensitiveName(name)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"MONKEY", "KEYSTONE_URL", "TOKENIZER_MODE", "PASSWORDLESS_LOGIN"})
  void isSensitiveName_whenOrdinaryNameContainsPartialWord_returnsFalse(String name) {
    assertThat(SecretRedactor.isSensitiveName(name)).isFalse();
  }

  @Test
  void redact_whenCredentialAssignmentsAndPrivateKeyPresent_masksValues() {
    String privateKey =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nprivate-material\n"
            + "-----END OPENSSH PRIVATE KEY-----";

    String result =
        redactor.redact(
            "API_KEY=api-value access-key:access-value privateKey=key-value "
                + "--api-key argument-value "
                + privateKey);

    assertThat(result)
        .contains(
            "API_KEY=<redacted>",
            "access-key:<redacted>",
            "privateKey=<redacted>",
            "--api-key <redacted>")
        .doesNotContain(
            "api-value", "access-value", "key-value", "argument-value", "private-material");
  }

  @Test
  void redactCommand_whenSensitiveOptionUsesFollowingArgument_masksThatArgument() {
    List<String> result =
        redactor.redactCommand(
            List.of("client", "--api-key", "api-value", "--mode", "ordinary-value"));

    assertThat(result)
        .containsExactly("client", "--api-key", "<redacted>", "--mode", "ordinary-value");
  }

  @Test
  void redactCommand_whenCurlUserUsesSeparateOrAttachedValue_masksCredentials() {
    List<String> result =
        redactor.redactCommand(
            List.of("curl", "--user", "first:password", "-u", "second:password", "-uthird"));

    assertThat(result)
        .containsExactly("curl", "--user", "<redacted>", "-u", "<redacted>", "-u<redacted>");
  }

  @Test
  void redact_whenBasicAuthorizationPresent_masksCredential() {
    String result = redactor.redact("Authorization: Basic dXNlcjpwYXNzd29yZA==");

    assertThat(result)
        .contains("Authorization: Basic <redacted>")
        .doesNotContain("dXNlcjpwYXNzd29yZA==");
  }

  @Test
  void redact_whenBasicAuthorizationIsShortOrJsonQuoted_masksCredential() {
    String shortCredential = redactor.redact("Authorization: Basic YTpi");
    String jsonCredential = redactor.redact("{\"Authorization\":\"Basic dXNlcjpwYXNzd29yZA==\"}");

    assertThat(shortCredential).isEqualTo("Authorization: Basic <redacted>");
    assertThat(jsonCredential)
        .contains("\"Authorization\":\"Basic <redacted>\"")
        .doesNotContain("dXNlcjpwYXNzd29yZA==");
  }

  @Test
  void redactCommand_whenShellTextContainsCurlOrQuotedCredential_masksWholeValue() {
    List<String> result =
        redactor.redactCommand(
            List.of(
                "/bin/bash",
                "-lc",
                "curl -u:password https://example.test; client --password \"hello world\""));

    assertThat(result.get(2))
        .contains("curl -u<redacted>", "--password <redacted>")
        .doesNotContain(":password", "hello", "world");
  }

  @Test
  void redact_whenOrdinaryBasicDescriptionPresent_preservesText() {
    assertThat(redactor.redact("Basic plan selected")).isEqualTo("Basic plan selected");
    assertThat(redactor.redact("tool -update cache")).isEqualTo("tool -update cache");
  }

  @Test
  void redact_whenBearerAuthorizationPresent_masksCredentialWithoutOrderingLeak() {
    String result = redactor.redact("Authorization: Bearer bearer-value");

    assertThat(result).contains("Authorization: Bearer <redacted>").doesNotContain("bearer-value");
  }

  @Test
  void redact_whenOrdinaryWordsContainCredentialFragments_preservesText() {
    String ordinary = "monkey=value tokenizer=fast passwordless=true keystone_url=https://host";

    assertThat(redactor.redact(ordinary)).isEqualTo(ordinary);
  }
}
