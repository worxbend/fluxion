package dev.sysboot.core;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Git repositories cloned into place.
 *
 * <p>Covers the pattern of cloning tooling that is not packaged: tmux plugin manager, Oh My Zsh
 * plugins, and similar. Destinations commonly use shell-style defaults such as {@code
 * ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}}, so they are expanded at execution time.
 */
public record GitRepoModule(ModuleName name, List<GitRepo> repos, boolean continueOnError)
    implements BootstrapModule {

  private static final Pattern IMMUTABLE_COMMIT = Pattern.compile("[0-9a-fA-F]{40}");

  public GitRepoModule {
    Objects.requireNonNull(name);
    repos = List.copyOf(Objects.requireNonNull(repos));
    if (repos.isEmpty()) {
      throw new IllegalArgumentException("git-repo requires at least one repository");
    }
    if (repos.stream().map(GitRepo::destination).distinct().count() != repos.size()) {
      throw new IllegalArgumentException("git-repo must not repeat a destination");
    }
  }

  /**
   * @param ref the exact immutable 40-hex commit to check out
   * @param update retained for config compatibility; immutable repositories only allow {@code NONE}
   * @param depth shallow-clone depth; empty means a full clone
   */
  public record GitRepo(
      String url,
      String destination,
      Optional<String> ref,
      Optional<Integer> depth,
      boolean submodules,
      GitRepoUpdate update) {

    public GitRepo {
      Objects.requireNonNull(url);
      Objects.requireNonNull(destination);
      Objects.requireNonNull(ref);
      Objects.requireNonNull(depth);
      Objects.requireNonNull(update);
      if (url.isBlank()) {
        throw new IllegalArgumentException("git-repo url must not be blank");
      }
      URI repositoryUri;
      try {
        repositoryUri = URI.create(url);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("git-repo url must be valid", e);
      }
      SourceUrlPolicy.requireHttps(repositoryUri, "git-repo url");
      if (repositoryUri.getQuery() != null || repositoryUri.getFragment() != null) {
        throw new IllegalArgumentException("git-repo url must not contain query or fragment data");
      }
      if (destination.isBlank()) {
        throw new IllegalArgumentException("git-repo destination must not be blank");
      }
      String commit =
          ref.filter(value -> IMMUTABLE_COMMIT.matcher(value).matches())
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "git-repo ref must be an exact immutable 40-hex commit"));
      ref = Optional.of(commit.toLowerCase(Locale.ROOT));
      if (depth.filter(value -> value < 1).isPresent()) {
        throw new IllegalArgumentException("git-repo depth must be at least 1");
      }
      if (update != GitRepoUpdate.NONE) {
        throw new IllegalArgumentException(
            "git-repo update must be none for an immutable commit checkout");
      }
    }

    public String commit() {
      return ref.orElseThrow();
    }
  }
}
