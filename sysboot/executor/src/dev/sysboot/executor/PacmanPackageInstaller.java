package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import java.util.List;

public final class PacmanPackageInstaller extends AbstractPackageInstaller {

  public PacmanPackageInstaller(
      ShellRunner shellRunner, SudoPasswordProvider sudoPasswordProvider) {
    super(shellRunner, sudoPasswordProvider);
  }

  @Override
  public boolean supports(PackageManagerKind kind) {
    return kind == PackageManagerKind.PACMAN;
  }

  @Override
  protected List<String> buildInstallCommand(PackageName packageName) {
    return List.of("sudo", "pacman", "-S", "--noconfirm", packageName.value());
  }
}
