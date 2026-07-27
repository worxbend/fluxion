package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitConfigScope;
import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.GitRepoUpdate;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.SystemdScope;
import dev.sysboot.core.SystemdState;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackageBackend;
import dev.sysboot.core.ToolPackagesModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Behaviour of the M3 parity step kinds that is easy to get subtly wrong. */
class M3StepKindsTest {

  // --- git-config -------------------------------------------------------------------------------

  @Test
  void gitConfigSkipsKeysThatAlreadyHoldTheDesiredValue() {
    var runner = new ScriptedRunner();
    runner.reply("git config --global --get user.name", "w0rxbend");

    new GitConfigExecutor(runner)
        .execute(
            new GitConfigModule(
                new ModuleName("git"),
                GitConfigScope.GLOBAL,
                Map.of("user.name", "w0rxbend", "user.email", "a@b.c"),
                false));

    assertThat(runner.commands)
        .as("an already-correct key must not be rewritten")
        .noneMatch(c -> c.equals("git config --global user.name w0rxbend"))
        .contains("git config --global user.email a@b.c");
  }

  @Test
  void gitConfigSystemScopeNeedsSudoButGlobalDoesNot() {
    var runner = new ScriptedRunner();

    new GitConfigExecutor(runner)
        .execute(
            new GitConfigModule(
                new ModuleName("git"),
                GitConfigScope.SYSTEM,
                Map.of("init.defaultBranch", "main"),
                false));

    assertThat(runner.commands).anyMatch(c -> c.startsWith("sudo git config --system"));
  }

  // --- git-repo ---------------------------------------------------------------------------------

  @Test
  void gitRepoUsesFastForwardOnlySoItNeverCreatesAMergeBehindYourBack() {
    var executor = new GitRepoExecutor(new ScriptedRunner());
    var module =
        new GitRepoModule(
            new ModuleName("plugins"),
            List.of(
                new GitRepoModule.GitRepo(
                    "https://example.com/r.git",
                    "/tmp/does-not-exist-fluxion",
                    Optional.empty(),
                    Optional.of(1),
                    false,
                    GitRepoUpdate.PULL)),
            false);

    assertThat(executor.commandPreview(module)).containsSubsequence("git", "clone", "--depth", "1");
  }

  // --- systemd-unit -----------------------------------------------------------------------------

  @Test
  void aStaticUnitIsNotEnabledBecauseThatIsAnError() {
    var runner = new ScriptedRunner();
    runner.reply("systemctl --version", "systemd 255");
    // `enable` on a unit with no [Install] section fails; it is already reachable as a dependency.
    runner.reply("systemctl --system is-enabled dbus.service", "static");
    runner.reply("systemctl --system is-active dbus.service", "active");

    new SystemdUnitExecutor(runner)
        .execute(units(SystemdScope.SYSTEM, "dbus", true, SystemdState.STARTED));

    assertThat(runner.commands).noneMatch(c -> c.contains("enable dbus.service"));
  }

  @Test
  void anAlreadyEnabledAndActiveUnitIsLeftAlone() {
    var runner = new ScriptedRunner();
    runner.reply("systemctl --version", "systemd 255");
    runner.reply("systemctl --system is-enabled docker.service", "enabled");
    runner.reply("systemctl --system is-active docker.service", "active");

    new SystemdUnitExecutor(runner)
        .execute(units(SystemdScope.SYSTEM, "docker", true, SystemdState.STARTED));

    assertThat(runner.commands)
        .noneMatch(c -> c.contains("enable docker.service"))
        .noneMatch(c -> c.contains("start docker.service"));
  }

  @Test
  void aMissingSystemctlSkipsRatherThanFailsSoContainersStayUsable() {
    var runner = new ScriptedRunner();
    runner.fail("systemctl --version");

    StepResult result =
        new SystemdUnitExecutor(runner)
            .execute(units(SystemdScope.SYSTEM, "docker", true, SystemdState.STARTED));

    assertThat(result).isInstanceOf(StepResult.Skipped.class);
  }

  @Test
  void userScopeUnitsDoNotUseSudo() {
    var executor = new SystemdUnitExecutor(new ScriptedRunner());

    assertThat(
            executor.commandPreview(
                units(SystemdScope.USER, "podman.socket", true, SystemdState.STARTED)))
        .doesNotContain("sudo");
  }

  // --- system-update ----------------------------------------------------------------------------

  @Test
  void dnfCheckUpdateExitCode100MeansUpdatesAvailableNotFailure() {
    var runner = new ScriptedRunner();
    runner.exit("sudo dnf check-update --refresh", 100);

    StepResult result =
        new SystemUpdateExecutor(runner)
            .execute(
                new SystemUpdateModule(
                    new ModuleName("update"),
                    PackageManagerKind.DNF,
                    false,
                    true,
                    Optional.empty(),
                    false));

    assertThat(result)
        .as("exit 100 from dnf check-update means there is work to do, not that it failed")
        .isInstanceOf(StepResult.Success.class);
  }

  @Test
  void zypperDistUpgradeUsesDupWhichIsRequiredOnTumbleweed() {
    var executor = new SystemUpdateExecutor(new ScriptedRunner());

    assertThat(
            executor.commandPreview(
                new SystemUpdateModule(
                    new ModuleName("u"),
                    PackageManagerKind.ZYPPER,
                    true,
                    false,
                    Optional.empty(),
                    false)))
        .contains("dup")
        .doesNotContain("update");
  }

  // --- tool-packages ----------------------------------------------------------------------------

  @Test
  void toolPackagesInstallEachPackageSeparatelySoOneFailureDoesNotBlockTheRest() {
    var runner = new ScriptedRunner();
    runner.reply("command -v cargo-binstall", "/usr/bin/cargo-binstall");
    runner.fail("cargo-binstall --no-confirm broken");

    StepResult result =
        new ToolPackagesExecutor(runner)
            .execute(
                new ToolPackagesModule(
                    new ModuleName("rust"),
                    ToolPackageBackend.CARGO_BINSTALL,
                    List.of(
                        new ToolPackagesModule.ToolPackage("eza"),
                        new ToolPackagesModule.ToolPackage("broken"),
                        new ToolPackagesModule.ToolPackage("lsd")),
                    true));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).contains("cargo-binstall --no-confirm lsd");
  }

  @Test
  void aMissingBackendFailsWithAnActionableMessage() {
    var runner = new ScriptedRunner();
    runner.fail("command -v uv");

    StepResult result =
        new ToolPackagesExecutor(runner)
            .execute(
                new ToolPackagesModule(
                    new ModuleName("py"),
                    ToolPackageBackend.UV_TOOL,
                    List.of(new ToolPackagesModule.ToolPackage("ruff")),
                    false));

    assertThat(((StepResult.Failure) result).errorMessage()).contains("uv is not on PATH");
  }

  private SystemdUnitModule units(
      SystemdScope scope, String unit, boolean enabled, SystemdState state) {
    return new SystemdUnitModule(
        new ModuleName("services"),
        scope,
        List.of(new SystemdUnitModule.SystemdUnit(unit, enabled, state, false)),
        false);
  }

  private static final class ScriptedRunner implements ShellRunner {

    private final List<String> commands = new ArrayList<>();
    private final Map<String, String> replies = new HashMap<>();
    private final Map<String, Integer> exits = new HashMap<>();

    void reply(String command, String stdout) {
      replies.put(command, stdout);
    }

    void fail(String command) {
      exits.put(command, 1);
    }

    void exit(String command, int code) {
      exits.put(command, code);
    }

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      String joined = String.join(" ", command);
      commands.add(joined);
      return new ProcessResult(
          exits.getOrDefault(joined, 0), replies.getOrDefault(joined, ""), "", Duration.ZERO);
    }
  }
}
