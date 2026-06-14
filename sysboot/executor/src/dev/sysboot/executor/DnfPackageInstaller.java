package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import java.util.List;

public final class DnfPackageInstaller extends AbstractPackageInstaller {

  public DnfPackageInstaller(ShellRunner shellRunner, SudoPasswordProvider sudoPasswordProvider) {
    super(shellRunner, sudoPasswordProvider);
  }

  @Override
  public boolean supports(PackageManagerKind kind) {
    return kind == PackageManagerKind.DNF;
  }

  @Override
  protected List<String> buildInstallCommand(PackageName packageName) {
    return List.of("sudo", "dnf", "install", "-y", packageName.value());
  }
}
