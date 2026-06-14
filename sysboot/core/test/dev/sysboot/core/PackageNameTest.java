package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PackageNameTest {

  @Test
  void constructor_whenValueIsValid_createsPackageName() {
    var name = new PackageName("ripgrep");
    assertThat(name.value()).isEqualTo("ripgrep");
  }

  @Test
  void constructor_whenValueHasLeadingWhitespace_stripsIt() {
    var name = new PackageName("  neovim  ");
    assertThat(name.value()).isEqualTo("neovim");
  }

  @Test
  void constructor_whenValueIsNull_throwsNullPointerException() {
    assertThatThrownBy(() -> new PackageName(null)).isInstanceOf(NullPointerException.class);
  }

  @Test
  void constructor_whenValueIsBlank_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> new PackageName("   "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
  }

  @ParameterizedTest
  @ValueSource(strings = {"pkg name", "pkg$name", "pkg;name", "pkg|name", "pkg&name", "pkg`name"})
  void constructor_whenValueContainsUnsafeChars_throwsIllegalArgumentException(String unsafe) {
    assertThatThrownBy(() -> new PackageName(unsafe))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unsafe");
  }

  @ParameterizedTest
  @ValueSource(strings = {"git", "python3-pip", "java-21-openjdk-devel", "libstdc++", "g++"})
  void constructor_whenValueIsValidPackageName_acceptsIt(String valid) {
    assertThat(new PackageName(valid).value()).isEqualTo(valid);
  }
}
