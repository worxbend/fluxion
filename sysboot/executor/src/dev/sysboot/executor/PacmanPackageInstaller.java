package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageManagerAction;
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

  @Override
  protected List<String> buildActionCommand(PackageManagerAction action) {
    return switch (action.action()) {
      case "sync-upgrade", "syu", "upgrade" -> syncUpgradeCommand(action);
      default -> throw new UnsupportedOperationException("Unsupported pacman action: " + action.action());
    };
  }

  private List<String> syncUpgradeCommand(PackageManagerAction action) {
    var command = new java.util.ArrayList<String>();
    command.add("sudo");
    command.add("pacman");
    command.add("-Syu");
    command.add("--noconfirm");
    command.addAll(action.args());
    return List.copyOf(command);
  }
}
