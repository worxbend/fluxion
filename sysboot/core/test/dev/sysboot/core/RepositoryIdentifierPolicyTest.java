package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class RepositoryIdentifierPolicyTest {

  private static final ModuleName NAME = new ModuleName("repository");
  private static final URI REPOSITORY_URL = URI.create("https://example.test/repository");
  private static final Sha256Digest DIGEST = new Sha256Digest("a".repeat(64));

  @Test
  void requireSafe_acceptsConservativeRepositoryIdentifiers() {
    assertThat(RepositoryIdentifierPolicy.requireSafe("repo-1.test_name", "Repository id"))
        .isEqualTo("repo-1.test_name");
  }

  @Test
  void modules_rejectSectionInjectionAndOptionLikeRemoteNames() {
    List<ThrowingCallable> constructors =
        List.of(
            () -> rpmModule("repo]\nenabled=1"),
            () -> zypperModule("repo\n[attacker]"),
            () -> flatpakModule("--user"),
            () -> pacmanModule("repo]\n[attacker]"));

    constructors.forEach(this::assertUnsafe);
  }

  @Test
  void sourceSetups_enforceTheSameIdentifierPolicy() {
    List<ThrowingCallable> constructors =
        List.of(
            () -> rpmSource("repo]\nenabled=1"),
            () -> zypperSource("repo\n[attacker]"),
            () -> flatpakSource("--user"),
            () -> pacmanSource("repo]\n[attacker]"));

    constructors.forEach(this::assertUnsafe);
  }

  private void assertUnsafe(ThrowingCallable constructor) {
    assertThatThrownBy(constructor)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must start with an alphanumeric character");
  }

  private RpmRepositoryModule rpmModule(String id) {
    return new RpmRepositoryModule(
        NAME,
        id,
        REPOSITORY_URL,
        Path.of("/etc/yum.repos.d/example.repo"),
        Optional.empty(),
        true,
        false);
  }

  private ZypperRepositoryModule zypperModule(String id) {
    return new ZypperRepositoryModule(
        NAME,
        id,
        REPOSITORY_URL,
        Path.of("/etc/zypp/repos.d/example.repo"),
        Optional.empty(),
        true,
        false,
        true);
  }

  private FlatpakRemoteModule flatpakModule(String remote) {
    return new FlatpakRemoteModule(NAME, remote, REPOSITORY_URL, true, Optional.of(DIGEST));
  }

  private PacmanRepositoryModule pacmanModule(String repository) {
    return new PacmanRepositoryModule(
        NAME,
        repository,
        REPOSITORY_URL,
        Path.of("/etc/pacman.conf"),
        Optional.empty(),
        Optional.empty(),
        true);
  }

  private RpmRepositorySourceSetup rpmSource(String id) {
    return new RpmRepositorySourceSetup(
        NAME,
        id,
        REPOSITORY_URL,
        Path.of("/etc/yum.repos.d/example.repo"),
        Optional.empty(),
        true,
        false);
  }

  private ZypperRepositorySourceSetup zypperSource(String id) {
    return new ZypperRepositorySourceSetup(
        NAME,
        id,
        REPOSITORY_URL,
        Path.of("/etc/zypp/repos.d/example.repo"),
        Optional.empty(),
        true,
        false);
  }

  private FlatpakRemoteSourceSetup flatpakSource(String remote) {
    return new FlatpakRemoteSourceSetup(NAME, remote, REPOSITORY_URL, true, Optional.of(DIGEST));
  }

  private PacmanRepositorySourceSetup pacmanSource(String repository) {
    return new PacmanRepositorySourceSetup(
        NAME,
        repository,
        REPOSITORY_URL,
        Path.of("/etc/pacman.conf"),
        Optional.empty(),
        Optional.empty(),
        true);
  }
}
