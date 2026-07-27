package dev.sysboot.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Git repositories cloned into place.
 *
 * <p>Covers the pattern of cloning tooling that is not packaged: tmux plugin manager, Oh My Zsh
 * plugins, and similar. Destinations commonly use shell-style defaults such as {@code
 * ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}}, so they are expanded at execution time.
 */
public record GitRepoModule(ModuleName name, List<GitRepo> repos, boolean continueOnError)
    implements BootstrapModule {

  public GitRepoModule {
    Objects.requireNonNull(name);
    repos = List.copyOf(Objects.requireNonNull(repos));
    if (repos.isEmpty()) {
      throw new IllegalArgumentException("git-repo requires at least one repository");
    }
  }

  /**
   * @param update what to do when the destination already exists
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
      if (destination.isBlank()) {
        throw new IllegalArgumentException("git-repo destination must not be blank");
      }
      if (depth.filter(value -> value < 1).isPresent()) {
        throw new IllegalArgumentException("git-repo depth must be at least 1");
      }
    }

    public GitRepo(String url, String destination) {
      this(url, destination, Optional.empty(), Optional.empty(), false, GitRepoUpdate.NONE);
    }
  }
}
