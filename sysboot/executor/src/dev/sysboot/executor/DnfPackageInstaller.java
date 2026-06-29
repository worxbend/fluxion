package dev.sysboot.executor;

import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import java.util.List;
import java.util.Set;

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

  @Override
  protected List<String> buildActionCommand(PackageManagerAction action) {
    return switch (action.action()) {
      case "check-update" -> command("check-update", action.args());
      case "upgrade" -> command("upgrade", concat("-y", action.args()));
      case "swap" -> command("swap", concat("-y", action.args()));
      case "groupupdate", "group-update" -> command("groupupdate", concat("-y", action.args()));
      default -> throw new UnsupportedOperationException("Unsupported dnf action: " + action.action());
    };
  }

  @Override
  protected Set<Integer> actionSuccessExitCodes(PackageManagerAction action) {
    if ("check-update".equals(action.action())) {
      return Set.of(0, 100);
    }
    return Set.of(0);
  }

  private List<String> command(String action, List<String> args) {
    var command = new java.util.ArrayList<String>();
    command.add("sudo");
    command.add("dnf");
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
}
