package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.GitRepoUpdate;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitRepoExecutorTest {

  private static final String URL = "https://example.test/plugin.git";
  private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

  @TempDir Path tempDir;

  @Test
  void existingDestinationWithWrongOriginFailsWithoutMutation() throws Exception {
    Path destination = existingRepository("wrong-origin");
    var runner = new RepositoryRunner(URL, COMMIT);
    runner.origin = "https://attacker.test/plugin.git";

    StepResult result = new GitRepoExecutor(runner).execute(module(destination));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands)
        .containsExactly(
            List.of("git", "-C", destination.toString(), "remote", "get-url", "origin"))
        .noneMatch(this::isMutation);
  }

  @Test
  void existingDestinationWithWrongHeadFailsWithoutResetOrOverwrite() throws Exception {
    Path destination = existingRepository("wrong-head");
    var runner = new RepositoryRunner(URL, "ffffffffffffffffffffffffffffffffffffffff");

    StepResult result = new GitRepoExecutor(runner).execute(module(destination));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands)
        .anyMatch(command -> command.contains("rev-parse"))
        .noneMatch(this::isMutation);
    assertThat(destination.resolve("keep-me")).exists();
  }

  @Test
  void existingDestinationWithTrackedChangesFailsReadOnly() throws Exception {
    Path destination = existingRepository("dirty-tracked");
    var runner = new RepositoryRunner(URL, COMMIT);
    runner.status = " M plugin.zsh\n";

    StepResult result = new GitRepoExecutor(runner).execute(module(destination));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).noneMatch(this::isMutation);
    assertThat(runner.environments.getLast()).containsEntry("GIT_OPTIONAL_LOCKS", "0");
  }

  @Test
  void existingDestinationWithUntrackedFilesFailsReadOnly() throws Exception {
    Path destination = existingRepository("dirty-untracked");
    var runner = new RepositoryRunner(URL, COMMIT);
    runner.status = "?? injected.zsh\n";

    assertThat(new GitRepoExecutor(runner).alreadyCloned(module(destination).repos().getFirst()))
        .isFalse();
    assertThat(runner.commands).noneMatch(this::isMutation);
  }

  @Test
  void pinnedCommitIsFetchedDirectlyWhenTheRemoteDefaultBranchHasAdvanced() {
    Path destination = tempDir.resolve("installed");
    var runner = new RepositoryRunner(URL, COMMIT);
    runner.materializeRepository = true;

    StepResult result = new GitRepoExecutor(runner).execute(module(destination));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(destination.resolve(".git")).isDirectory();
    assertThat(runner.commands)
        .anyMatch(
            command ->
                command.containsAll(List.of("init", "--quiet"))
                    && command.getLast().contains(".installed.sysboot-stage-"))
        .anyMatch(command -> command.containsAll(List.of("remote", "add", "origin", URL)))
        .anyMatch(
            command ->
                command.containsAll(List.of("fetch", "origin", COMMIT))
                    && command.contains("protocol.file.allow=never"))
        .anyMatch(
            command ->
                command.containsAll(List.of("checkout", "--detach", "FETCH_HEAD"))
                    && command.contains("-C"));
  }

  @Test
  void failedCheckoutCleansTheStageAndLeavesDestinationAbsent() throws Exception {
    Path destination = tempDir.resolve("not-installed");
    var runner = new RepositoryRunner(URL, COMMIT);
    runner.materializeRepository = true;
    runner.failCheckout = true;

    StepResult result = new GitRepoExecutor(runner).execute(module(destination));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(destination).doesNotExist();
    try (var children = Files.list(tempDir)) {
      assertThat(children.map(path -> path.getFileName().toString()).toList())
          .noneMatch(name -> name.contains("sysboot-stage"));
    }
  }

  private Path existingRepository(String name) throws IOException {
    Path destination = tempDir.resolve(name);
    Files.createDirectories(destination.resolve(".git"));
    Files.writeString(destination.resolve("keep-me"), "unchanged");
    return destination;
  }

  private GitRepoModule module(Path destination) {
    return new GitRepoModule(
        new ModuleName("plugins"),
        List.of(
            new GitRepoModule.GitRepo(
                URL,
                destination.toString(),
                Optional.of(COMMIT),
                Optional.empty(),
                false,
                GitRepoUpdate.NONE)),
        false);
  }

  private boolean isMutation(List<String> command) {
    return command.contains("checkout")
        || command.contains("init")
        || command.contains("fetch")
        || command.contains("pull")
        || command.contains("reset");
  }

  private static final class RepositoryRunner implements ShellRunner {

    private final List<List<String>> commands = new ArrayList<>();
    private final List<Map<String, String>> environments = new ArrayList<>();
    private final String head;
    private String origin;
    private boolean materializeRepository;
    private boolean failCheckout;
    private String status = "";

    private RepositoryRunner(String expectedUrl, String head) {
      this.origin = expectedUrl;
      this.head = head;
    }

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(List.copyOf(command));
      environments.add(Map.copyOf(env));
      if (command.contains("init") && materializeRepository) {
        createRepository(Path.of(command.getLast()));
      }
      if (command.contains("checkout") && failCheckout) {
        return result(1, "", "commit unavailable");
      }
      if (command.contains("get-url")) {
        return result(0, origin + "\n", "");
      }
      if (command.contains("rev-parse")) {
        return result(0, head + "\n", "");
      }
      if (command.contains("status")) {
        return result(0, status, "");
      }
      return result(0, "", "");
    }

    private void createRepository(Path destination) {
      try {
        Files.createDirectories(destination.resolve(".git"));
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    private ProcessResult result(int exitCode, String stdout, String stderr) {
      return new ProcessResult(exitCode, stdout, stderr, Duration.ZERO);
    }
  }
}
