package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Command(name = "list", description = "List modules in a config file")
public final class ListCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Override
  public void run() {
    var context = ApplicationContext.create(true);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());

    System.out.printf("Profile: %s  OS: %s%n%n", config.profileName().value(), config.target());
    System.out.printf("%-30s %-12s %s%n", "MODULE", "TYPE", "ITEMS");
    System.out.println("-".repeat(70));

    for (BootstrapModule module : config.modules()) {
      System.out.printf(
          "%-30s %-12s %s%n", module.name().value(), moduleType(module), moduleDescription(module));
    }
  }

  private String moduleType(BootstrapModule module) {
    return switch (module) {
      case PackageModule pm -> "📦 packages";
      case FlatpakModule ignored -> "🗃 flatpak";
      case ShellScriptModule ignored -> "📜 script";
      case CompiledBinaryModule ignored -> "⬇ binary";
      case ZypperModule ignored -> "📦 zypper";
      case DotbotModule ignored -> "🔗 dotbot";
      case DefaultShellModule ignored -> "🐚 shell";
      case OhMyZshModule ignored -> "🐚 oh-my-zsh";
      case ToolchainModule ignored -> "🧰 toolchain";
      case NerdFontModule ignored -> "🔤 nerd-font";
      case ShellReloadModule ignored -> "🔄 reload";
      case ShellCommandModule ignored -> "📜 command";
    };
  }

  private String moduleDescription(BootstrapModule module) {
    return switch (module) {
      case PackageModule pm -> pm.packages().size() + " packages (" + pm.packageManager() + ")";
      case FlatpakModule fm -> fm.appIds().size() + " apps from " + fm.remote();
      case ShellScriptModule sm -> sm.script().toString();
      case CompiledBinaryModule bm -> bm.binaryName() + " from " + bm.url();
      case ZypperModule zm -> zm.packages().size() + " packages (zypper)";
      case DotbotModule dm -> dm.config().toString();
      case DefaultShellModule dsm -> dsm.shellPath().toString();
      case OhMyZshModule omz -> omz.installDir().toString();
      case ToolchainModule tm -> tm.kind().name().toLowerCase();
      case NerdFontModule nfm -> nfm.config().families().size() + " families";
      case ShellReloadModule srm -> srm.shell().binaryName();
      case ShellCommandModule sc -> sc.commands().size() + " commands";
    };
  }
}
