package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import java.util.List;

public final class AptPackageInstaller extends AbstractPackageInstaller {

  public AptPackageInstaller(ShellRunner shellRunner, SudoPasswordProvider sudoPasswordProvider) {
    super(shellRunner, sudoPasswordProvider);
  }

  @Override
  public boolean supports(PackageManagerKind kind) {
    return kind == PackageManagerKind.APT;
  }

  @Override
  protected List<String> buildInstallCommand(PackageName packageName) {
    return List.of("sudo", "apt-get", "install", "-y", packageName.value());
  }

  @Override
  protected List<String> buildActionCommand(PackageManagerAction action) {
    return switch (action.action()) {
      case "update" -> command("update", action.args());
      case "upgrade" -> command("upgrade", concat("-y", action.args()));
      case "dist-upgrade" -> command("dist-upgrade", concat("-y", action.args()));
      default -> throw new UnsupportedOperationException("Unsupported apt action: " + action.action());
    };
  }

  private List<String> command(String action, List<String> args) {
    return concat("sudo", "apt-get", action, args);
  }

  private List<String> concat(String first, List<String> rest) {
    var command = new java.util.ArrayList<String>();
    command.add(first);
    command.addAll(rest);
    return List.copyOf(command);
  }

  private List<String> concat(String first, String second, String third, List<String> rest) {
    var command = new java.util.ArrayList<String>();
    command.add(first);
    command.add(second);
    command.add(third);
    command.addAll(rest);
    return List.copyOf(command);
  }
}
