package dev.sysboot.executor;

import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Clones repositories that are not available as packages, and optionally updates them. */
public final class GitRepoExecutor {

  private static final Duration TIMEOUT = Duration.ofMinutes(10);

  private final ShellRunner shellRunner;

  public GitRepoExecutor(ShellRunner shellRunner) {
    this.shellRunner = shellRunner;
  }

  public StepResult execute(GitRepoModule module) {
    var failures = new ArrayList<String>();
    for (GitRepoModule.GitRepo repo : module.repos()) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      apply(repo).ifPresent(failures::add);
    }
    return StepOutcome.of(module.name(), failures, module.continueOnError());
  }

  /** True when the destination is already a worktree for this repository. */
  public boolean alreadyCloned(GitRepoModule.GitRepo repo) {
    Path destination = ShellPaths.expand(repo.destination());
    return Files.isDirectory(destination.resolve(".git"));
  }

  public List<String> commandPreview(GitRepoModule module) {
    var preview = new ArrayList<String>();
    module.repos().forEach(repo -> preview.addAll(cloneCommand(repo)));
    return List.copyOf(preview);
  }

  private java.util.Optional<String> apply(GitRepoModule.GitRepo repo) {
    if (alreadyCloned(repo)) {
      return update(repo);
    }
    ProcessResult result = run(cloneCommand(repo));
    if (!result.isSuccess()) {
      return java.util.Optional.of("clone " + repo.url() + ": " + StepOutcome.detail(result));
    }
    return repo.submodules() ? submodules(repo) : java.util.Optional.empty();
  }

  private java.util.Optional<String> update(GitRepoModule.GitRepo repo) {
    String destination = ShellPaths.expand(repo.destination()).toString();
    List<String> command =
        switch (repo.update()) {
          case NONE -> List.of();
          // --ff-only rather than a plain pull: a merge commit created behind the user's back in a
          // repository they may have edited is not something a bootstrapper should ever do.
          case PULL -> List.of("git", "-C", destination, "pull", "--ff-only");
          case RESET_HARD -> List.of("git", "-C", destination, "reset", "--hard", "@{u}");
        };
    if (command.isEmpty()) {
      return java.util.Optional.empty();
    }
    ProcessResult result = run(command);
    return result.isSuccess()
        ? java.util.Optional.empty()
        : java.util.Optional.of("update " + repo.destination() + ": " + StepOutcome.detail(result));
  }

  private java.util.Optional<String> submodules(GitRepoModule.GitRepo repo) {
    ProcessResult result =
        run(
            List.of(
                "git",
                "-C",
                ShellPaths.expand(repo.destination()).toString(),
                "submodule",
                "update",
                "--init",
                "--recursive"));
    return result.isSuccess()
        ? java.util.Optional.empty()
        : java.util.Optional.of(
            "submodules " + repo.destination() + ": " + StepOutcome.detail(result));
  }

  private List<String> cloneCommand(GitRepoModule.GitRepo repo) {
    var command = new ArrayList<>(List.of("git", "clone"));
    repo.depth().ifPresent(depth -> command.addAll(List.of("--depth", String.valueOf(depth))));
    repo.ref().ifPresent(ref -> command.addAll(List.of("--branch", ref)));
    if (repo.submodules()) {
      command.add("--recurse-submodules");
    }
    command.add(repo.url());
    command.add(ShellPaths.expand(repo.destination()).toString());
    return List.copyOf(command);
  }

  private ProcessResult run(List<String> command) {
    return shellRunner.run(command, Map.of(), TIMEOUT);
  }
}
