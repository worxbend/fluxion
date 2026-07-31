package dev.sysboot.executor;

import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Provisions immutable, origin-verified Git repository checkouts. */
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

  StepResult executeItem(GitRepoModule.GitRepo repo) {
    Optional<String> failure = apply(repo);
    return failure
        .<StepResult>map(
            message -> new StepResult.Failure(repo.destination(), message, 1, Duration.ZERO))
        .orElseGet(() -> new StepResult.Success(repo.destination(), Duration.ZERO));
  }

  /** True only when the destination has the configured origin and exact detached commit. */
  public boolean alreadyCloned(GitRepoModule.GitRepo repo) {
    try {
      Path destination = destination(repo);
      return existingRepository(destination) && verify(repo, destination).isEmpty();
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  public List<String> commandPreview(GitRepoModule module) {
    var preview = new ArrayList<String>();
    for (GitRepoModule.GitRepo repo : module.repos()) {
      Path staged = Path.of("<staged-destination>");
      preview.addAll(initCommand(staged));
      preview.addAll(remoteCommand(repo, staged));
      preview.addAll(fetchCommand(repo, staged));
      preview.addAll(checkoutCommand(staged));
    }
    return List.copyOf(preview);
  }

  List<String> commandPreview(GitRepoModule.GitRepo repo) {
    Path staged = Path.of("<staged-destination>");
    var preview = new ArrayList<String>();
    preview.addAll(initCommand(staged));
    preview.addAll(remoteCommand(repo, staged));
    preview.addAll(fetchCommand(repo, staged));
    preview.addAll(checkoutCommand(staged));
    return List.copyOf(preview);
  }

  private Optional<String> apply(GitRepoModule.GitRepo repo) {
    Path destination;
    try {
      destination = destination(repo);
    } catch (IllegalArgumentException e) {
      return Optional.of(e.getMessage());
    }
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      if (!existingRepository(destination)) {
        return Optional.of("git-repo destination exists but is not a regular Git worktree");
      }
      return verify(repo, destination);
    }
    return install(repo, destination);
  }

  private Optional<String> install(GitRepoModule.GitRepo repo, Path destination) {
    Path staged;
    try {
      staged = stagedDestination(destination);
    } catch (IOException | IllegalArgumentException e) {
      return Optional.of("git-repo could not prepare a staged destination");
    }
    boolean installed = false;
    try {
      Optional<String> failure = runStep(initCommand(staged), "repository initialization");
      if (failure.isEmpty()) {
        failure = runStep(remoteCommand(repo, staged), "origin configuration");
      }
      if (failure.isEmpty()) {
        failure = runStep(fetchCommand(repo, staged), "exact commit fetch");
      }
      if (failure.isEmpty()) {
        failure = runStep(checkoutCommand(staged), "detached checkout");
      }
      if (failure.isEmpty() && repo.submodules()) {
        failure = runStep(submoduleCommand(staged), "submodule checkout");
      }
      if (failure.isEmpty()) {
        failure = verify(repo, staged);
      }
      if (failure.isPresent()) {
        return failure;
      }
      moveIntoPlace(staged, destination);
      installed = true;
      return Optional.empty();
    } catch (IOException e) {
      return Optional.of("git-repo could not install the verified staged checkout");
    } finally {
      if (!installed) {
        deleteStaged(staged);
      }
    }
  }

  private Optional<String> verify(GitRepoModule.GitRepo repo, Path destination) {
    ProcessResult origin =
        runReadOnlyGit(List.of("git", "-C", destination.toString(), "remote", "get-url", "origin"));
    if (!origin.isSuccess()) {
      return Optional.of("git-repo could not verify the destination origin");
    }
    if (!origin.stdout().strip().equals(repo.url())) {
      return Optional.of("git-repo destination origin does not match the configured HTTPS URL");
    }

    ProcessResult head =
        runReadOnlyGit(
            List.of("git", "-C", destination.toString(), "rev-parse", "--verify", "HEAD"));
    if (!head.isSuccess()) {
      return Optional.of("git-repo could not verify the destination HEAD");
    }
    if (!head.stdout().strip().equalsIgnoreCase(repo.commit())) {
      return Optional.of("git-repo destination HEAD does not match the configured commit");
    }
    ProcessResult status = runReadOnlyGit(statusCommand(destination));
    if (!status.isSuccess()) {
      return Optional.of("git-repo could not verify the destination worktree");
    }
    if (!status.stdout().isBlank()) {
      return Optional.of("git-repo destination has tracked or untracked modifications");
    }
    return Optional.empty();
  }

  private List<String> statusCommand(Path destination) {
    return List.of(
        "git",
        "-c",
        "core.fsmonitor=false",
        "-C",
        destination.toString(),
        "status",
        "--porcelain=v1",
        "--untracked-files=all",
        "--ignore-submodules=none");
  }

  private Optional<String> runStep(List<String> command, String operation) {
    ProcessResult result = run(command);
    return result.isSuccess()
        ? Optional.empty()
        : Optional.of("git-repo " + operation + " failed: " + StepOutcome.detail(result));
  }

  private List<String> initCommand(Path destination) {
    return List.of("git", "init", "--quiet", "--", destination.toString());
  }

  private List<String> remoteCommand(GitRepoModule.GitRepo repo, Path destination) {
    return List.of("git", "-C", destination.toString(), "remote", "add", "origin", repo.url());
  }

  private List<String> fetchCommand(GitRepoModule.GitRepo repo, Path destination) {
    var command =
        new ArrayList<>(
            List.of(
                "git", "-c", "protocol.file.allow=never", "-C", destination.toString(), "fetch"));
    repo.depth().ifPresent(depth -> command.addAll(List.of("--depth", String.valueOf(depth))));
    command.add("origin");
    command.add(repo.commit());
    return List.copyOf(command);
  }

  private List<String> checkoutCommand(Path destination) {
    return List.of("git", "-C", destination.toString(), "checkout", "--detach", "FETCH_HEAD");
  }

  private List<String> submoduleCommand(Path destination) {
    return List.of(
        "git",
        "-c",
        "protocol.allow=never",
        "-c",
        "protocol.https.allow=always",
        "-C",
        destination.toString(),
        "submodule",
        "update",
        "--init",
        "--recursive");
  }

  private Path destination(GitRepoModule.GitRepo repo) {
    Path destination = ShellPaths.expand(repo.destination());
    if (!destination.isAbsolute()) {
      throw new IllegalArgumentException("git-repo destination must resolve to an absolute path");
    }
    Path normalized = destination.normalize();
    if (normalized.getParent() == null || normalized.getFileName() == null) {
      throw new IllegalArgumentException("git-repo destination must not be a filesystem root");
    }
    return normalized;
  }

  private boolean existingRepository(Path destination) {
    return Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
        && Files.isDirectory(destination.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
  }

  private Path stagedDestination(Path destination) throws IOException {
    Path parent = PathRequirements.parent(destination, "Git repository destination");
    Files.createDirectories(parent);
    if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("destination parent is not a directory");
    }
    return parent.resolve("." + destination.getFileName() + ".sysboot-stage-" + UUID.randomUUID());
  }

  private void moveIntoPlace(Path staged, Path destination) throws IOException {
    if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("destination appeared during staging");
    }
    try {
      Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(staged, destination);
    }
  }

  private void deleteStaged(Path staged) {
    if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try {
      Files.walkFileTree(
          staged,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.deleteIfExists(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure)
                throws IOException {
              if (failure != null) {
                throw failure;
              }
              Files.deleteIfExists(directory);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException ignored) {
      // The final destination remains untouched; a named staging directory may need manual cleanup.
    }
  }

  private ProcessResult run(List<String> command) {
    return shellRunner.run(command, Map.of(), TIMEOUT);
  }

  private ProcessResult runReadOnlyGit(List<String> command) {
    return shellRunner.run(command, Map.of("GIT_OPTIONAL_LOCKS", "0"), TIMEOUT);
  }
}
