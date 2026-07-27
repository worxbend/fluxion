package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.UserGroupsModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The interesting behaviour is not running {@code usermod} — it is noticing that the command
 * succeeded and the machine still is not in the desired state until the user logs out.
 */
class UserGroupsExecutorTest {

  @Test
  void addsOnlyTheGroupsTheUserIsNotAlreadyIn() {
    var runner = scripted();
    runner.reply("id -nG bob", "bob wheel docker");
    runner.reply("getent group libvirt", "libvirt:x:110:");

    new UserGroupsExecutor(runner).execute(module("bob", "docker", "libvirt"));

    assertThat(runner.commands)
        .as("docker is already present and must not be re-added")
        .noneMatch(c -> c.equals("sudo usermod -aG docker bob"))
        .contains("sudo usermod -aG libvirt bob");
  }

  @Test
  void reportsAGroupThatDoesNotExistInsteadOfCreatingIt() {
    var runner = scripted();
    runner.reply("id -nG bob", "bob");
    runner.fail("getent group docker");

    StepResult result = new UserGroupsExecutor(runner).execute(module("bob", "docker"));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("does not exist")
        .contains("createMissing");
    assertThat(runner.commands).noneMatch(c -> c.startsWith("sudo groupadd"));
  }

  @Test
  void createsAMissingGroupOnlyWhenAskedTo() {
    var runner = scripted();
    runner.reply("id -nG bob", "bob");

    new UserGroupsExecutor(runner)
        .execute(
            new UserGroupsModule(
                new ModuleName("groups"),
                Optional.of("bob"),
                List.of("docker"),
                true,
                true,
                Optional.empty(),
                false));

    assertThat(runner.commands).contains("sudo groupadd -f docker", "sudo usermod -aG docker bob");
  }

  @Test
  void aGroupInTheDatabaseButNotInTheSessionNeedsALogout() {
    var runner = scripted();
    runner.reply("id -nG bob", "bob wheel docker");
    runner.reply("id -nG", "bob wheel");

    List<String> pending =
        new UserGroupsExecutor(runner).groupsPendingLogout(currentUserModule("docker"));

    assertThat(pending)
        .as("usermod exits 0 but the running session has no docker group until logout")
        .containsExactly("docker");
  }

  @Test
  void aGroupTheSessionAlreadyHasNeedsNoLogout() {
    var runner = scripted();
    runner.reply("id -nG bob", "bob wheel docker");
    runner.reply("id -nG", "bob wheel docker");

    assertThat(new UserGroupsExecutor(runner).groupsPendingLogout(currentUserModule("docker")))
        .isEmpty();
  }

  @Test
  void changingAnotherAccountNeverRequiresLoggingThisOneOut() {
    var runner = scripted();
    runner.reply("id -nG someone-else", "someone-else docker");
    runner.reply("id -nG", "bob");

    assertThat(new UserGroupsExecutor(runner).groupsPendingLogout(module("someone-else", "docker")))
        .isEmpty();
  }

  @Test
  void theCheckpointCanBeSuppressedForContainersAndImageBuilds() {
    var runner = scripted();
    runner.reply("id -nG bob", "bob docker");
    runner.reply("id -nG", "bob");

    var module =
        new UserGroupsModule(
            new ModuleName("groups"),
            Optional.of("bob"),
            List.of("docker"),
            false,
            false,
            Optional.empty(),
            false);

    assertThat(new UserGroupsExecutor(runner).groupsPendingLogout(module)).isEmpty();
  }

  @Test
  void theCommandPreviewShowsWhatWouldRun() {
    assertThat(
            new UserGroupsExecutor(scripted()).commandPreview(module("bob", "docker", "libvirt")))
        .containsExactly("sudo", "usermod", "-aG", "docker,libvirt", "bob");
  }

  private UserGroupsModule module(String user, String... groups) {
    return new UserGroupsModule(
        new ModuleName("groups"),
        Optional.of(user),
        List.of(groups),
        false,
        true,
        Optional.empty(),
        false);
  }

  private UserGroupsModule currentUserModule(String... groups) {
    return new UserGroupsModule(
        new ModuleName("groups"),
        Optional.of(System.getProperty("user.name")),
        List.of(groups),
        false,
        true,
        Optional.empty(),
        false);
  }

  private ScriptedRunner scripted() {
    var runner = new ScriptedRunner();
    // groupsPendingLogout resolves the current user; make the fixture stable regardless of who
    // actually runs the tests.
    runner.reply("id -nG " + System.getProperty("user.name"), "bob wheel docker");
    return runner;
  }

  private static final class ScriptedRunner implements ShellRunner {

    private final List<String> commands = new ArrayList<>();
    private final Map<String, String> replies = new HashMap<>();
    private final List<String> failures = new ArrayList<>();

    void reply(String command, String stdout) {
      replies.put(command, stdout);
    }

    void fail(String command) {
      failures.add(command);
    }

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      String joined = String.join(" ", command);
      commands.add(joined);
      if (failures.contains(joined)) {
        return new ProcessResult(1, "", "not found", Duration.ZERO);
      }
      return new ProcessResult(0, replies.getOrDefault(joined, ""), "", Duration.ZERO);
    }
  }
}
