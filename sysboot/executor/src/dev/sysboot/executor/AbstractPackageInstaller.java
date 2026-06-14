package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SudoPasswordProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;

abstract sealed class AbstractPackageInstaller implements PackageManagerExecutor
    permits DnfPackageInstaller,
        PacmanPackageInstaller,
        ParuPackageInstaller,
        YayPackageInstaller,
        AptPackageInstaller,
        ZypperPackageInstaller {

  protected static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);

  protected final ShellRunner shellRunner;
  protected final SudoPasswordProvider sudoPasswordProvider;

  AbstractPackageInstaller(ShellRunner shellRunner, SudoPasswordProvider sudoPasswordProvider) {
    this.shellRunner = shellRunner;
    this.sudoPasswordProvider = sudoPasswordProvider;
  }

  protected abstract List<String> buildInstallCommand(PackageName packageName);

  @Override
  public StepResult install(PackageName packageName) {
    List<String> command = buildInstallCommand(packageName);
    ProcessResult result = shellRunner.run(command, Map.of(), INSTALL_TIMEOUT);
    if (result.isSuccess()) {
      return new StepResult.Success(packageName.value(), result.elapsed());
    }
    return new StepResult.Failure(
        packageName.value(),
        result.stdout() + result.stderr(),
        result.exitCode(),
        result.elapsed());
  }
}
