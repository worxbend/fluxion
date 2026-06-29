package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellCommandItem;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellEnvironmentVariable;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ShellCommandExecutorTest {

  @TempDir Path tempDir;

  @Test
  void execute_whenCommandUsesArgv_preservesDirectCommandBoundary() {
    var runner = new FakeShellRunner(List.of(new ProcessResult(0, "", "", Duration.ZERO)));
    var item = directItem("git-default", List.of("git", "config", "init.defaultBranch", "main"));

    StepResult result = new ShellCommandExecutor(runner).execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).containsExactly(List.of("git", "config", "init.defaultBranch", "main"));
  }

  @Test
  void execute_whenAllowedExitCodeMatches_treatsCommandAsSuccess() {
    var runner = new FakeShellRunner(List.of(new ProcessResult(75, "", "", Duration.ZERO)));
    var item = new ShellCommandItem(
        "interrupt-code",
        Optional.empty(),
        Optional.of(List.of("tool", "maybe")),
        "/bin/bash",
        Optional.empty(),
        List.of(),
        false,
        List.of(0, 75),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Duration.ofSeconds(5));

    StepResult result = new ShellCommandExecutor(runner).execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void execute_whenCreatesPathExists_skipsShellCommand(@TempDir Path dir) throws Exception {
    Path marker = dir.resolve("created");
    Files.createFile(marker);
    var runner = new FakeShellRunner(List.of());
    var item = new ShellCommandItem(
        "already-created",
        Optional.of("touch " + marker),
        Optional.empty(),
        "/bin/bash",
        Optional.empty(),
        List.of(),
        false,
        List.of(0),
        Optional.of(marker),
        Optional.empty(),
        Optional.empty(),
        Duration.ofSeconds(5));

    StepResult result = new ShellCommandExecutor(runner).execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).isEmpty();
  }

  @Test
  void execute_whenUnlessCommandSucceeds_skipsMainCommand() {
    var runner = new FakeShellRunner(List.of(new ProcessResult(0, "", "", Duration.ZERO)));
    var item = new ShellCommandItem(
        "guarded",
        Optional.of("touch /tmp/marker"),
        Optional.empty(),
        "/bin/bash",
        Optional.empty(),
        List.of(),
        false,
        List.of(0),
        Optional.empty(),
        Optional.of("test -f /tmp/marker"),
        Optional.empty(),
        Duration.ofSeconds(5));

    StepResult result = new ShellCommandExecutor(runner).execute(module(item));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).containsExactly(List.of("/bin/bash", "-lc", "test -f /tmp/marker"));
  }

  @Test
  void execute_whenOutputContainsSecrets_redactsFailureMessageAndPreview() {
    var runner =
        new FakeShellRunner(
            List.of(
                new ProcessResult(
                    1,
                    "TOKEN=abc123 https://user:pass@example.test/install",
                    "Bearer abc123",
                    Duration.ZERO)));
    var item =
        new ShellCommandItem(
            "secret-command",
            Optional.empty(),
            Optional.of(List.of("curl", "https://user:pass@example.test/install")),
            "/bin/bash",
            Optional.empty(),
            List.of(new ShellEnvironmentVariable("TOKEN", "abc123", true)),
            false,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Duration.ofSeconds(5));

    StepResult result = new ShellCommandExecutor(runner).execute(module(item));
    List<String> preview = new ShellCommandExecutor(runner).commandPreview(item);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .doesNotContain("abc123", "user:pass")
        .contains("<redacted>");
    assertThat(preview).doesNotContain("https://user:pass@example.test/install");
  }

  private ShellCommandItem directItem(String name, List<String> argv) {
    return new ShellCommandItem(
        name,
        Optional.empty(),
        Optional.of(argv),
        "/bin/bash",
        Optional.empty(),
        List.of(),
        false,
        List.of(0),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Duration.ofSeconds(5));
  }

  private ShellCommandModule module(ShellCommandItem item) {
    return new ShellCommandModule(
        new ModuleName("commands"), List.of(item), "/bin/bash", Optional.empty(), false,
        Optional.empty());
  }

  private static final class FakeShellRunner implements ShellRunner {
    private final ArrayList<List<String>> commands = new ArrayList<>();
    private final ArrayList<ProcessResult> results;

    private FakeShellRunner(List<ProcessResult> results) {
      this.results = new ArrayList<>(results);
    }

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(command);
      return results.isEmpty() ? new ProcessResult(0, "", "", Duration.ZERO) : results.removeFirst();
    }
  }
}
