package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class GitRepoModuleTest {

  private static final String COMMIT = "0123456789ABCDEF0123456789ABCDEF01234567";

  @Test
  void repositoryRequiresHttpsWithoutCredentialsOrRequestData() {
    assertThatThrownBy(() -> repo("http://example.test/plugin.git", COMMIT, GitRepoUpdate.NONE))
        .hasMessageContaining("HTTPS without user-info");
    assertThatThrownBy(() -> repo("git@example.test:owner/plugin.git", COMMIT, GitRepoUpdate.NONE))
        .hasMessageContaining("url must be valid");
    assertThatThrownBy(
            () -> repo("https://user:secret@example.test/plugin.git", COMMIT, GitRepoUpdate.NONE))
        .hasMessageContaining("HTTPS without user-info");
    assertThatThrownBy(
            () -> repo("https://example.test/plugin.git?token=secret", COMMIT, GitRepoUpdate.NONE))
        .hasMessageContaining("query or fragment");
  }

  @Test
  void repositoryRequiresAnExactImmutableCommit() {
    assertThatThrownBy(
            () ->
                new GitRepoModule.GitRepo(
                    "https://example.test/plugin.git",
                    "/tmp/plugin",
                    Optional.empty(),
                    Optional.empty(),
                    false,
                    GitRepoUpdate.NONE))
        .hasMessageContaining("40-hex commit");
    assertThatThrownBy(() -> repo("https://example.test/plugin.git", "main", GitRepoUpdate.NONE))
        .hasMessageContaining("40-hex commit");
  }

  @Test
  void repositoryRejectsMutableUpdatePoliciesAndNormalizesTheCommit() {
    assertThatThrownBy(
            () -> repo("https://example.test/plugin.git", COMMIT, GitRepoUpdate.RESET_HARD))
        .hasMessageContaining("update must be none");

    assertThat(repo("https://example.test/plugin.git", COMMIT, GitRepoUpdate.NONE).commit())
        .isEqualTo(COMMIT.toLowerCase());
  }

  private GitRepoModule.GitRepo repo(String url, String commit, GitRepoUpdate update) {
    return new GitRepoModule.GitRepo(
        url, "/tmp/plugin", Optional.of(commit), Optional.empty(), false, update);
  }
}
