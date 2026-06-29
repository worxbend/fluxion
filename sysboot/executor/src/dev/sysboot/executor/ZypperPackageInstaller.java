package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import java.util.List;

public final class ZypperPackageInstaller extends AbstractPackageInstaller {

  public ZypperPackageInstaller(
      ShellRunner shellRunner, SudoPasswordProvider sudoPasswordProvider) {
    super(shellRunner, sudoPasswordProvider);
  }

  @Override
  public boolean supports(PackageManagerKind kind) {
    return kind == PackageManagerKind.ZYPPER;
  }

  @Override
  protected List<String> buildInstallCommand(PackageName packageName) {
    return List.of("sudo", "zypper", "install", "-y", packageName.value());
  }

  @Override
  protected List<String> buildActionCommand(PackageManagerAction action) {
    return switch (action.action()) {
      case "refresh" -> command("refresh", action.args());
      case "update" -> command("update", concat("-y", action.args()));
      case "dup" -> command("dup", concat("-y", action.args()));
      case "dup-from" -> command("dup", dupFromArgs(action.args()));
      default -> throw new UnsupportedOperationException("Unsupported zypper action: " + action.action());
    };
  }

  private List<String> command(String action, List<String> args) {
    var command = new java.util.ArrayList<String>();
    command.add("sudo");
    command.add("zypper");
    command.add("--non-interactive");
    command.add(action);
    command.addAll(args);
    return List.copyOf(command);
  }

  private List<String> concat(String first, List<String> rest) {
    var args = new java.util.ArrayList<String>();
    args.add(first);
    args.addAll(rest);
    return List.copyOf(args);
  }

  private List<String> dupFromArgs(List<String> actionArgs) {
    var args = new java.util.ArrayList<String>();
    args.add("-y");
    args.add("--from");
    args.addAll(actionArgs);
    return List.copyOf(args);
  }
}
