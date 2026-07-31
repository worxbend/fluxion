package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ShellEnvironmentVariableTest {

  @Test
  void constructor_whenNameIsPortable_acceptsIt() {
    assertThatCode(() -> new ShellEnvironmentVariable("_API_TOKEN_2", "value", true))
        .doesNotThrowAnyException();
  }

  @Test
  void constructor_whenNameIsNotPortable_rejectsIt() {
    assertThatThrownBy(() -> new ShellEnvironmentVariable("API-TOKEN", "value", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("portable");
  }

  @Test
  void constructor_whenNameOrValueContainsNul_rejectsIt() {
    assertThatThrownBy(() -> new ShellEnvironmentVariable("API\0TOKEN", "value", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NUL");
    assertThatThrownBy(() -> new ShellEnvironmentVariable("API_TOKEN", "value\0tail", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("NUL");
  }
}
