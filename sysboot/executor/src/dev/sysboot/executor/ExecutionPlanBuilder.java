package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OhMyZshModule;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.ZypperModule;
import java.util.List;
import java.util.Optional;

public final class ExecutionPlanBuilder {

  private final PackageManagerExecutorRegistry packageManagerRegistry;
  private final ModuleExecutorRegistry moduleExecutorRegistry;
  private final PhaseExecutionPlanner phasePlanner;
  private final SourceSetupExecutor sourceSetupExecutor;

  public ExecutionPlanBuilder(PackageManagerExecutorRegistry packageManagerRegistry) {
    this.packageManagerRegistry = packageManagerRegistry;
    this.moduleExecutorRegistry =
        new ModuleExecutorRegistry(
            List.of(
                new PackageModuleExecutor(packageManagerRegistry),
                new SdkmanModuleExecutor(new DefaultShellRunner())));
    this.phasePlanner = new PhaseExecutionPlanner();
    this.sourceSetupExecutor =
        new SourceSetupExecutor(
            new AptRepositoryInstaller(new DefaultShellRunner()),
            new RpmRepositoryInstaller(new DefaultShellRunner()),
            new PacmanRepositoryInstaller(new DefaultShellRunner()),
            new ZypperRepositoryInstaller(new DefaultShellRunner()),
            new FlatpakRemoteInstaller(new DefaultShellRunner()));
  }

  public ExecutionPlan build(BootstrapConfig config) {
    List<ExecutionPlan.Module> sourceSetups =
        config.sourceSetups().stream().map(this::sourceSetup).toList();
    List<ExecutionPlan.Phase> phases =
        phasePlanner.plan(config.phases()).stream().map(this::phase).toList();
    return new ExecutionPlan(
        config.profileName().value(), sourceSetups, phases, config.skippedPlanEntries());
  }

  private ExecutionPlan.Module sourceSetup(SourceSetup setup) {
    var item = sourceSetupExecutor.item(setup);
    return new ExecutionPlan.Module(
        setup.name().value(),
        "source-setup",
        List.of(
            new ExecutionPlan.Item(item, Optional.of(sourceSetupExecutor.commandPreview(setup)))));
  }

  private ExecutionPlan.Phase phase(Phase phase) {
    return new ExecutionPlan.Phase(
        phase.name().value(),
        phase.dependsOn().stream().map(dep -> dep.value()).toList(),
        restartEffect(phase.restartPolicy()),
        phase.modules().stream().map(this::module).toList());
  }

  private ExecutionPlan.Module module(BootstrapModule module) {
    return new ExecutionPlan.Module(
        module.name().value(),
        moduleType(module),
        items(module).stream().map(item -> item(module, item)).toList());
  }

  private List<ModuleItem> items(BootstrapModule module) {
    return moduleExecutorRegistry
        .find(module)
        .map(executor -> executor.items(module))
        .orElseGet(() -> fallbackItems(module));
  }

  private ExecutionPlan.Item item(BootstrapModule module, ModuleItem item) {
    Optional<List<String>> commandPreview = commandPreview(module, item);
    return new ExecutionPlan.Item(item, commandPreview);
  }

  private Optional<List<String>> commandPreview(BootstrapModule module, ModuleItem item) {
    if (module instanceof PackageModule packageModule
        && item.itemType() == ItemType.PACKAGE_ACTION) {
      return packageActionCommand(packageModule, item);
    }
    if (module instanceof FlatpakModule flatpakModule) {
      return Optional.of(List.of("flatpak", "install", "-y", flatpakModule.remote(), item.key()));
    }
    if (module instanceof FlatpakRemoteModule flatpakRemoteModule) {
      return Optional.of(flatpakRemoteCommand(flatpakRemoteModule));
    }
    if (module instanceof ShellScriptModule shellScriptModule) {
      return shellScriptModule.items().stream()
          .filter(script -> script.name().equals(item.key()))
          .findFirst()
          .map(script -> new ShellScriptExecutor(new DefaultShellRunner()).commandPreview(script));
    }
    if (module instanceof ShellCommandModule shellCommandModule) {
      return shellCommandModule.items().stream()
          .filter(command -> command.name().equals(item.key()))
          .findFirst()
          .map(command -> new ShellCommandExecutor(new DefaultShellRunner()).commandPreview(command));
    }
    if (module instanceof SdkmanModule sdkmanModule) {
      return sdkmanModule.packages().stream()
          .filter(pkg -> pkg.itemKey().equals(item.key()))
          .findFirst()
          .map(pkg -> new SdkmanModuleExecutor(new DefaultShellRunner()).commandPreview(pkg));
    }
    if (module instanceof AptRepositoryModule aptRepositoryModule) {
      return Optional.of(
          new AptRepositoryInstaller(new DefaultShellRunner()).addCommand(aptRepositoryModule));
    }
    if (module instanceof RpmRepositoryModule rpmRepositoryModule) {
      return Optional.of(
          new RpmRepositoryInstaller(new DefaultShellRunner()).addCommand(rpmRepositoryModule));
    }
    if (module instanceof PacmanRepositoryModule pacmanRepositoryModule) {
      return Optional.of(
          new PacmanRepositoryInstaller(new DefaultShellRunner())
              .addCommand(pacmanRepositoryModule));
    }
    if (module instanceof FileWriteModule fileWriteModule) {
      return fileWriteModule.items().stream()
          .filter(file -> file.itemKey().equals(item.key()))
          .findFirst()
          .map(file -> new FileWriteExecutor(new DefaultShellRunner()).dryRunCommand(file));
    }
    return item.packageManager()
        .map(
            kind ->
                packageManagerRegistry
                    .forKind(kind)
                    .installCommand(new dev.sysboot.core.PackageName(item.key())));
  }

  private Optional<List<String>> packageActionCommand(PackageModule module, ModuleItem item) {
    return actionIndex(item.key())
        .map(module.actions()::get)
        .map(action -> packageManagerRegistry.forKind(module.packageManager()).actionCommand(action));
  }

  private Optional<Integer> actionIndex(String key) {
    if (!key.startsWith("action[") || !key.endsWith("]")) {
      return Optional.empty();
    }
    try {
      return Optional.of(Integer.parseInt(key.substring("action[".length(), key.length() - 1)));
    } catch (NumberFormatException e) {
      return Optional.empty();
    }
  }

  private List<String> flatpakRemoteCommand(FlatpakRemoteModule module) {
    if (module.system()) {
      return List.of(
          "flatpak", "remote-add", "--if-not-exists", module.remote(), module.url().toString());
    }
    return List.of(
        "flatpak",
        "--user",
        "remote-add",
        "--if-not-exists",
        module.remote(),
        module.url().toString());
  }

  private List<ModuleItem> fallbackItems(BootstrapModule module) {
    return switch (module) {
      case AptRepositoryModule arm ->
          List.of(
              new ModuleItem(arm.name(), arm.sourceListPath().toString(), ItemType.APT_REPOSITORY));
      case RpmRepositoryModule rrm ->
          List.of(
              new ModuleItem(rrm.name(), rrm.repoFilePath().toString(), ItemType.RPM_REPOSITORY));
      case PacmanRepositoryModule prm ->
          List.of(new ModuleItem(prm.name(), prm.repositoryName(), ItemType.PACMAN_REPOSITORY));
      case FileWriteModule fwm -> new FileWriteExecutor(new DefaultShellRunner()).items(fwm);
      case FlatpakModule fm ->
          fm.appIds().stream()
              .map(app -> new ModuleItem(fm.name(), app, ItemType.FLATPAK))
              .toList();
      case FlatpakRemoteModule frm ->
          List.of(new ModuleItem(frm.name(), frm.remote(), ItemType.FLATPAK_REMOTE));
      case ShellScriptModule sm ->
          sm.items().stream()
              .map(
                  item ->
                      new ModuleItem(
                          sm.name(), item.name(), item.key(), ItemType.SHELL_SCRIPT,
                          Optional.empty()))
              .toList();
      case CompiledBinaryModule bm ->
          List.of(new ModuleItem(bm.name(), bm.installPath().toString(), ItemType.COMPILED_BINARY));
      case DotbotModule dm ->
          List.of(new ModuleItem(dm.name(), dm.config().toString(), ItemType.DOTBOT));
      case DefaultShellModule dsm ->
          List.of(new ModuleItem(dsm.name(), dsm.shellPath().toString(), ItemType.DEFAULT_SHELL));
      case OhMyZshModule omz ->
          List.of(new ModuleItem(omz.name(), omz.installDir().toString(), ItemType.OH_MY_ZSH));
      case ToolchainModule tm ->
          List.of(new ModuleItem(tm.name(), tm.kind().name().toLowerCase(), ItemType.TOOLCHAIN));
      case NerdFontModule nfm ->
          List.of(new ModuleItem(nfm.name(), "nerd-fonts", ItemType.NERD_FONT));
      case ShellReloadModule srm ->
          List.of(new ModuleItem(srm.name(), srm.shell().binaryName(), ItemType.SHELL_RELOAD));
      case ShellCommandModule sc ->
          sc.items().stream()
              .map(item -> new ModuleItem(sc.name(), item.name(), ItemType.SHELL_COMMAND))
              .toList();
      case AssertModule am ->
          List.of(new ModuleItem(am.name(), am.name().value(), ItemType.ASSERT));
      case ManualModule mm ->
          List.of(new ModuleItem(mm.name(), mm.name().value(), ItemType.MANUAL));
      case InterruptModule im ->
          List.of(new ModuleItem(im.name(), im.name().value(), ItemType.INTERRUPT));
      case SdkmanModule ignored -> throw new IllegalStateException("SDKMAN executor missing");
      case PackageModule ignored -> throw new IllegalStateException("Package executor missing");
      case ZypperModule ignored -> throw new IllegalStateException("Zypper executor missing");
    };
  }

  private ExecutionPlan.RestartEffect restartEffect(RestartPolicy policy) {
    return switch (policy) {
      case RestartPolicy.None ignored -> ExecutionPlan.RestartEffect.NONE;
      case RestartPolicy.PromptLogout ignored -> ExecutionPlan.RestartEffect.PROMPT_LOGOUT;
      case RestartPolicy.RequiresNewShell ignored -> ExecutionPlan.RestartEffect.REQUIRES_NEW_SHELL;
    };
  }

  private String moduleType(BootstrapModule module) {
    return switch (module) {
      case PackageModule ignored -> "packages";
      case AptRepositoryModule ignored -> "apt-repository";
      case RpmRepositoryModule ignored -> "rpm-repository";
      case PacmanRepositoryModule ignored -> "pacman-repository";
      case FileWriteModule ignored -> "file-writes";
      case FlatpakModule ignored -> "flatpak";
      case FlatpakRemoteModule ignored -> "flatpak-remote";
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
      case InterruptModule ignored -> "interrupt";
      case SdkmanModule ignored -> "sdkman-packages";
    };
  }
}
