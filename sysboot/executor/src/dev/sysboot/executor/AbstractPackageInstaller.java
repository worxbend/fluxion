package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SudoPasswordProvider;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

  protected List<String> buildActionCommand(PackageManagerAction action) {
    throw new UnsupportedOperationException("Package manager action is not supported");
  }

  protected Set<Integer> actionSuccessExitCodes(PackageManagerAction action) {
    return Set.of(0);
  }

  @Override
  public List<String> actionCommand(PackageManagerAction action) {
    return buildActionCommand(action);
  }

  @Override
  public List<String> installCommand(PackageName packageName) {
    return buildInstallCommand(packageName);
  }

  @Override
  public StepResult install(PackageName packageName) {
    List<String> command = installCommand(packageName);
    return runCommand(packageName.value(), command, Set.of(0));
  }

  @Override
  public StepResult runAction(PackageManagerAction action) {
    return runCommand(action.action(), actionCommand(action), actionSuccessExitCodes(action));
  }

  private StepResult runCommand(String item, List<String> command, Set<Integer> successExitCodes) {
    ProcessResult result = shellRunner.run(command, Map.of(), INSTALL_TIMEOUT);
    if (successExitCodes.contains(result.exitCode())) {
      return new StepResult.Success(item, result.elapsed());
    }
    return new StepResult.Failure(item, result.stdout() + result.stderr(), result.exitCode(), result.elapsed());
  }
}
