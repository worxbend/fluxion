package dev.sysboot.tui;

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
import java.util.List;

final class SelectionEntryCatalog {

  private SelectionEntryCatalog() {}

  static List<String> entries(BootstrapModule module) {
    return switch (module) {
      case PackageModule packageModule ->
          packageModule.packages().stream().map(packageName -> packageName.value()).toList();
      case ZypperModule zypperModule ->
          zypperModule.packages().stream().map(packageName -> packageName.value()).toList();
      case FlatpakModule flatpakModule -> flatpakModule.appIds();
      case ShellCommandModule shellCommandModule -> shellCommandModule.commands();
      case NerdFontModule nerdFontModule -> nerdFontModule.config().families();
      case CompiledBinaryModule compiledBinaryModule -> List.of(compiledBinaryModule.binaryName());
      case ShellScriptModule shellScriptModule -> List.of(shellScriptModule.script().toString());
      case DotbotModule dotbotModule -> List.of(dotbotModule.config().toString());
      case DefaultShellModule defaultShellModule ->
          List.of(defaultShellModule.shellPath().toString());
      case OhMyZshModule ohMyZshModule -> List.of(ohMyZshModule.installDir().toString());
      case ToolchainModule toolchainModule -> List.of(toolchainModule.kind().name().toLowerCase());
      case ShellReloadModule shellReloadModule ->
          List.of(shellReloadModule.shell().name().toLowerCase());
    };
  }
}
