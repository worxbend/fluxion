package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import java.util.List;

public final class CargoPackageInstaller extends AbstractPackageInstaller {

  public CargoPackageInstaller(ShellRunner shellRunner, SudoPasswordProvider sudoPasswordProvider) {
    super(shellRunner, sudoPasswordProvider);
  }

  @Override
  public boolean supports(PackageManagerKind kind) {
    return kind == PackageManagerKind.CARGO;
  }

  @Override
  protected List<String> buildInstallCommand(PackageName packageName) {
    return List.of("cargo", "install", packageName.value());
  }
}
