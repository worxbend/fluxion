package dev.sysboot.cli.command;

import dev.sysboot.app.ApplicationContext;
import dev.sysboot.cli.option.GlobalOptions;
import dev.sysboot.cli.output.JsonOutput;
import dev.sysboot.cli.output.OutputFormat;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "list", description = "List modules in a config file")
public final class ListCommand implements Runnable {

  @Mixin private GlobalOptions options;

  @Spec private CommandSpec spec;

  @Option(
      names = "--format",
      defaultValue = "text",
      description = "Output format: ${COMPLETION-CANDIDATES}")
  private OutputFormat format;

  @Override
  public void run() {
    var context = ApplicationContext.create(true);
    BootstrapConfig config = context.configLoader().load(options.resolvedConfigFile());

    if (format == OutputFormat.JSON) {
      JsonOutput.write(spec.commandLine().getOut(), jsonList(config));
      return;
    }

    var out = spec.commandLine().getOut();
    out.printf("Profile: %s  OS: %s%n%n", config.profileName().value(), config.target());
    out.printf("%-30s %-12s %s%n", "MODULE", "TYPE", "ITEMS");
    out.println("-".repeat(70));

    for (BootstrapModule module : config.modules()) {
      out.printf(
          "%-30s %-12s %s%n", module.name().value(), moduleType(module), moduleDescription(module));
    }
  }

  private Map<String, Object> jsonList(BootstrapConfig config) {
    var output = new LinkedHashMap<String, Object>();
    output.put("profileName", config.profileName().value());
    output.put("target", config.target().toString());
    output.put("modules", config.modules().stream().map(this::jsonModule).toList());
    return output;
  }

  private Map<String, Object> jsonModule(BootstrapModule module) {
    var output = new LinkedHashMap<String, Object>();
    output.put("name", module.name().value());
    output.put("type", jsonModuleType(module));
    output.put("description", moduleDescription(module));
    output.put("itemCount", itemCount(module));
    return output;
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
      case AssertModule ignored -> "✓ assert";
      case ManualModule ignored -> "☐ manual";
    };
  }

  private String jsonModuleType(BootstrapModule module) {
    return switch (module) {
      case PackageModule ignored -> "packages";
      case FlatpakModule ignored -> "flatpak";
      case ShellScriptModule ignored -> "shell-script";
      case CompiledBinaryModule ignored -> "compiled-binary";
      case ZypperModule ignored -> "zypper";
      case DotbotModule ignored -> "dotbot";
      case DefaultShellModule ignored -> "default-shell";
      case OhMyZshModule ignored -> "oh-my-zsh";
      case ToolchainModule ignored -> "toolchain";
      case NerdFontModule ignored -> "nerd-font";
      case ShellReloadModule ignored -> "shell-reload";
      case ShellCommandModule ignored -> "shell-command";
      case AssertModule ignored -> "assert";
      case ManualModule ignored -> "manual";
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
      case AssertModule am -> am.command();
      case ManualModule mm -> mm.message();
    };
  }

  private int itemCount(BootstrapModule module) {
    return switch (module) {
      case PackageModule pm -> pm.packages().size();
      case FlatpakModule fm -> fm.appIds().size();
      case ShellCommandModule sc -> sc.commands().size();
      case NerdFontModule nfm -> nfm.config().families().size();
      default -> 1;
    };
  }
}
