package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SudoPasswordProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PackageActionCommandTest {

  private final ShellRunner shellRunner =
      (command, env, timeout) -> new ProcessResult(0, "", "", Duration.ZERO);
  private final SudoPasswordProvider sudoPasswordProvider = prompt -> java.util.Optional.empty();

  @Test
  void aptActionCommand_rendersDirectArgv() {
    var installer = new AptPackageInstaller(shellRunner, sudoPasswordProvider);

    List<String> command =
        installer.actionCommand(new PackageManagerAction("dist-upgrade", List.of("--with-new-pkgs")));

    assertThat(command)
        .containsExactly("sudo", "apt-get", "dist-upgrade", "-y", "--with-new-pkgs");
  }

  @Test
  void dnfActionCommand_rendersDirectArgv() {
    var installer = new DnfPackageInstaller(shellRunner, sudoPasswordProvider);

    List<String> command =
        installer.actionCommand(new PackageManagerAction("swap", List.of("ffmpeg-free", "ffmpeg")));

    assertThat(command).containsExactly("sudo", "dnf", "swap", "-y", "ffmpeg-free", "ffmpeg");
  }

  @Test
  void dnfCheckUpdate_allowsExitCodeOneHundred() {
    var runner = new CapturingRunner(new ProcessResult(100, "", "", Duration.ZERO));
    var installer = new DnfPackageInstaller(runner, sudoPasswordProvider);

    StepResult result = installer.runAction(new PackageManagerAction("check-update", List.of()));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands).containsExactly(List.of("sudo", "dnf", "check-update"));
  }

  @Test
  void pacmanActionCommand_rendersDirectArgv() {
    var installer = new PacmanPackageInstaller(shellRunner, sudoPasswordProvider);

    List<String> command =
        installer.actionCommand(new PackageManagerAction("sync-upgrade", List.of("--needed")));

    assertThat(command).containsExactly("sudo", "pacman", "-Syu", "--noconfirm", "--needed");
  }

  @Test
  void zypperActionCommand_rendersDirectArgv() {
    var installer = new ZypperPackageInstaller(shellRunner, sudoPasswordProvider);

    List<String> command =
        installer.actionCommand(new PackageManagerAction("dup-from", List.of("packman")));

    assertThat(command)
        .containsExactly("sudo", "zypper", "--non-interactive", "dup", "-y", "--from", "packman");
  }

  private static final class CapturingRunner implements ShellRunner {
    private final ProcessResult result;
    private final java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();

    private CapturingRunner(ProcessResult result) {
      this.result = result;
    }

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(command);
      return result;
    }
  }
}
