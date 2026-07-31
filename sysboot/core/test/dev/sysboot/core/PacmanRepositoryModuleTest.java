package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PacmanRepositoryModuleTest {

  @ParameterizedTest
  @ValueSource(strings = {"repo[name", "repo]name", "repo\rname", "repo\nname"})
  void constructor_whenRepositoryNameContainsSectionDelimiter_rejectsIt(String repositoryName) {
    assertThatThrownBy(() -> module(repositoryName))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must start with an alphanumeric character");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Required\nInclude = /tmp/attacker",
        "Required UnknownToken",
        "Required\tDatabaseOptional"
      })
  void constructor_whenSigLevelIsNotKnownSingleLineGrammar_rejectsIt(String sigLevel) {
    assertThatThrownBy(
            () ->
                new PacmanRepositoryModule(
                    new ModuleName("repository"),
                    "repository",
                    URI.create("https://example.test/$repo/$arch"),
                    Path.of("/etc/pacman.conf"),
                    Optional.of(sigLevel),
                    Optional.empty(),
                    true))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("supported single-line tokens");
  }

  @Test
  void moduleAndSourceSetup_rejectUnsafeConfigAndIncludePaths() {
    List<ThrowingCallable> constructors =
        List.of(
            () -> module(Path.of("pacman.conf"), Optional.empty()),
            () -> module(Path.of("/etc/../tmp/pacman.conf"), Optional.empty()),
            () -> module(Path.of("/etc/pacman.conf"), Optional.of(Path.of("mirrorlist"))),
            () ->
                new PacmanRepositorySourceSetup(
                    new ModuleName("repository"),
                    "repository",
                    URI.create("https://example.test/$repo/$arch"),
                    Path.of("/etc/pacman.conf"),
                    Optional.empty(),
                    Optional.of(Path.of("/etc/pacman.d/list\nInclude = /tmp/attacker")),
                    true));

    constructors.forEach(
        constructor ->
            assertThatThrownBy(constructor)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pacman"));
  }

  private PacmanRepositoryModule module(String repositoryName) {
    return module(repositoryName, Path.of("/etc/pacman.conf"), Optional.empty());
  }

  private PacmanRepositoryModule module(Path config, Optional<Path> include) {
    return module("repository", config, include);
  }

  private PacmanRepositoryModule module(
      String repositoryName, Path config, Optional<Path> include) {
    return new PacmanRepositoryModule(
        new ModuleName("repository"),
        repositoryName,
        URI.create("https://example.test/$repo/$arch"),
        config,
        Optional.empty(),
        include,
        true);
  }
}
