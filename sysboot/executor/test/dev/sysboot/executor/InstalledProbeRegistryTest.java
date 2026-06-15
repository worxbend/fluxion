package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InstalledProbeRegistryTest {

  @Test
  void probe_whenAptPackage_usesDpkgQuery() {
    var runner = new RecordingShellRunner();
    var registry = packageProbeRegistry(runner);

    registry.probe(packageItem(PackageManagerKind.APT));

    assertThat(runner.command()).startsWith("dpkg-query");
  }

  @Test
  void probe_whenPacmanPackage_usesPacmanQuery() {
    var runner = new RecordingShellRunner();
    var registry = packageProbeRegistry(runner);

    registry.probe(packageItem(PackageManagerKind.PACMAN));

    assertThat(runner.command()).containsExactly("pacman", "-Q", "git");
  }

  @Test
  void probe_whenParuPackage_usesPacmanQuery() {
    var runner = new RecordingShellRunner();
    var registry = packageProbeRegistry(runner);

    registry.probe(packageItem(PackageManagerKind.PARU));

    assertThat(runner.command()).containsExactly("pacman", "-Q", "git");
  }

  @Test
  void probe_whenDnfPackage_usesRpmQuery() {
    var runner = new RecordingShellRunner();
    var registry = packageProbeRegistry(runner);

    registry.probe(packageItem(PackageManagerKind.DNF));

    assertThat(runner.command()).containsExactly("rpm", "-q", "git");
  }

  @Test
  void probe_whenZypperPackage_usesRpmQuery() {
    var runner = new RecordingShellRunner();
    var registry = packageProbeRegistry(runner);

    registry.probe(packageItem(PackageManagerKind.ZYPPER));

    assertThat(runner.command()).containsExactly("rpm", "-q", "git");
  }

  private static InstalledProbeRegistry packageProbeRegistry(ShellRunner runner) {
    return new InstalledProbeRegistry(
        List.of(
            new DnfPackageProbe(runner),
            new PacmanPackageProbe(runner),
            new AptPackageProbe(runner),
            new ZypperPackageProbe(runner)));
  }

  private static ModuleItem packageItem(PackageManagerKind kind) {
    return ModuleItem.packageItem(new ModuleName("packages"), "git", kind);
  }

  private static final class RecordingShellRunner implements ShellRunner {
    private List<String> command = List.of();

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      this.command = List.copyOf(command);
      return new ProcessResult(1, "", "", Duration.ZERO);
    }

    List<String> command() {
      return command;
    }
  }
}
