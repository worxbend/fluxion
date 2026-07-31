package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DefaultShellModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.GpgKeyModule;
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
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellReloadModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SourceSetup;
import dev.sysboot.core.SystemSettingModule;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackagesModule;
import dev.sysboot.core.ToolchainModule;
import dev.sysboot.core.UserGroupsModule;
import dev.sysboot.core.ZypperModule;
import dev.sysboot.core.ZypperRepositoryModule;
import java.util.List;
import java.util.Optional;

public final class ExecutionPlanBuilder {

  private final PackageManagerExecutorRegistry packageManagerRegistry;
  private final PhaseExecutionPlanner phasePlanner;
  private final SourceSetupExecutor sourceSetupExecutor;
  private final PhaseExecutors previewExecutors;
  private final CompiledBinaryInstaller binaryInstaller;

  public ExecutionPlanBuilder(PackageManagerExecutorRegistry packageManagerRegistry) {
    this.packageManagerRegistry = packageManagerRegistry;
    this.phasePlanner = new PhaseExecutionPlanner();
    var runner = new DefaultShellRunner();
    this.previewExecutors = PhaseExecutors.forRunner(runner);
    this.binaryInstaller = new CompiledBinaryInstaller(runner);
    this.sourceSetupExecutor =
        new SourceSetupExecutor(
            new AptRepositoryInstaller(runner),
            new RpmRepositoryInstaller(runner),
            new PacmanRepositoryInstaller(runner),
            new ZypperRepositoryInstaller(runner),
            new FlatpakRemoteInstaller(runner));
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
        phase.modules().stream().map(module -> module(phase, module)).toList());
  }

  private ExecutionPlan.Module module(Phase phase, BootstrapModule module) {
    return new ExecutionPlan.Module(
        module.name().value(),
        moduleType(module),
        items(module).stream().map(item -> item(phase, module, item)).toList());
  }

  private List<ModuleItem> items(BootstrapModule module) {
    return ModuleItemCatalog.items(module);
  }

  private ExecutionPlan.Item item(Phase phase, BootstrapModule module, ModuleItem item) {
    Optional<List<String>> commandPreview =
        commandPreview(module, item).map(command -> transformForPhase(phase, module, command));
    return new ExecutionPlan.Item(item, commandPreview);
  }

  private List<String> transformForPhase(
      Phase phase, BootstrapModule module, List<String> command) {
    if (phase.restartPolicy() instanceof RestartPolicy.RequiresNewShell newShell
        && !(module instanceof ManualModule)
        && !(module instanceof InterruptModule)) {
      return new LoginShellWrappingRunner(new DefaultShellRunner(), newShell.shell())
          .wrapCommand(command);
    }
    return command;
  }

  private Optional<List<String>> commandPreview(BootstrapModule module, ModuleItem item) {
    Optional<SourceSetup> sourceSetup = directSourceSetup(module);
    if (sourceSetup.isPresent()) {
      return Optional.of(sourceSetupExecutor.commandPreview(sourceSetup.orElseThrow()));
    }
    Optional<StepBinding> binding = StepBinding.find(module);
    if (binding.isPresent()) {
      return Optional.of(binding.orElseThrow().commandPreview(module, previewExecutors));
    }
    if (module instanceof PackageModule packageModule
        && item.itemType() == ItemType.PACKAGE_ACTION) {
      return packageActionCommand(packageModule, item);
    }
    if (module instanceof FlatpakModule flatpakModule) {
      return Optional.of(List.of("flatpak", "install", "-y", flatpakModule.remote(), item.key()));
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
          .map(
              command ->
                  new ShellCommandExecutor(new DefaultShellRunner()).commandPreview(command));
    }
    if (module instanceof GpgKeyModule gpgKeyModule) {
      return gpgKeyModule.keys().stream()
          .filter(key -> key.itemKey().equals(item.key()))
          .findFirst()
          .map(key -> new GpgKeyExecutor(new DefaultShellRunner()).commandPreview(key));
    }
    if (module instanceof BinstallerModule binstallerModule) {
      return Optional.of(
          new BinstallerExecutor(new DefaultShellRunner()).commandPreview(binstallerModule));
    }
    if (module instanceof DotbotModule dotbotModule) {
      return Optional.of(new DotbotExecutor(new DefaultShellRunner()).commandPreview(dotbotModule));
    }
    if (module instanceof NerdFontModule nerdFontModule) {
      return Optional.of(
          new NerdFontExecutor(new DefaultShellRunner()).commandPreview(nerdFontModule));
    }
    if (module instanceof SdkmanModule sdkmanModule) {
      return sdkmanModule.packages().stream()
          .filter(pkg -> pkg.itemKey().equals(item.key()))
          .findFirst()
          .map(pkg -> new SdkmanModuleExecutor(new DefaultShellRunner()).commandPreview(pkg));
    }
    if (module instanceof FileWriteModule fileWriteModule) {
      return fileWriteModule.items().stream()
          .filter(file -> file.itemKey().equals(item.key()))
          .findFirst()
          .map(file -> new FileWriteExecutor(new DefaultShellRunner()).dryRunCommand(file));
    }
    if (module instanceof CompiledBinaryModule binaryModule) {
      return Optional.of(binaryInstaller.dryRunCommand(binaryModule));
    }
    if (module instanceof UserGroupsModule userGroupsModule) {
      return userGroupsModule.groups().stream()
          .filter(group -> userGroupsModule.itemKey(group).equals(item.key()))
          .findFirst()
          .map(group -> previewExecutors.userGroups().commandPreview(userGroupsModule, group));
    }
    if (module instanceof GitConfigModule gitConfigModule) {
      return gitConfigModule.sortedKeys().stream()
          .filter(key -> gitConfigModule.itemKey(key).equals(item.key()))
          .findFirst()
          .map(key -> previewExecutors.gitConfig().commandPreview(gitConfigModule, key));
    }
    if (module instanceof GitRepoModule gitRepoModule) {
      return gitRepoModule.repos().stream()
          .filter(repo -> repo.destination().equals(item.key()))
          .findFirst()
          .map(previewExecutors.gitRepo()::commandPreview);
    }
    if (module instanceof SystemdUnitModule systemdModule) {
      return systemdModule.units().stream()
          .filter(unit -> unit.qualifiedName().equals(item.key()))
          .findFirst()
          .map(unit -> previewExecutors.systemdUnit().commandPreview(systemdModule, unit));
    }
    if (module instanceof SystemSettingModule settingModule) {
      return Optional.of(
          previewExecutors.systemSetting().commandPreview(settingModule, item.key()));
    }
    if (module instanceof ToolPackagesModule toolPackagesModule) {
      return toolPackagesModule.packages().stream()
          .filter(tool -> tool.name().equals(item.key()))
          .findFirst()
          .map(tool -> previewExecutors.toolPackages().commandPreview(toolPackagesModule, tool));
    }
    if (module instanceof AssertModule assertion) {
      return Optional.of(List.of(assertion.shell(), "-lc", assertion.command()));
    }
    if (module instanceof ManualModule manual) {
      return Optional.of(List.of("manual", manual.message()));
    }
    if (module instanceof InterruptModule interrupt) {
      return Optional.of(
          List.of(
              "interrupt",
              interrupt.name().value(),
              "message=" + interrupt.message(),
              "resumeFrom=" + interrupt.resumeFrom().name().toLowerCase(),
              "exitCode=" + interrupt.exitCode()));
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
        .map(
            action ->
                packageManagerRegistry.forKind(module.packageManager()).actionCommand(action));
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

  private Optional<SourceSetup> directSourceSetup(BootstrapModule module) {
    return switch (module) {
      case AptRepositoryModule apt -> Optional.of(apt.asSourceSetup());
      case RpmRepositoryModule rpm -> Optional.of(rpm.asSourceSetup());
      case PacmanRepositoryModule pacman -> Optional.of(pacman.asSourceSetup());
      case ZypperRepositoryModule zypper -> Optional.of(zypper.asSourceSetup());
      case FlatpakRemoteModule flatpak -> Optional.of(flatpak.asSourceSetup());
      default -> Optional.empty();
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
      case BinstallerModule ignored -> "binstaller-profile";
      case UserGroupsModule ignored -> "user-groups";
      case ZypperRepositoryModule ignored -> "zypper-repository";
      case GitConfigModule ignored -> "git-config";
      case GitRepoModule ignored -> "git-repo";
      case SystemdUnitModule ignored -> "systemd-unit";
      case SystemSettingModule ignored -> "system-setting";
      case SystemUpdateModule ignored -> "system-update";
      case GpgKeyModule ignored -> "gpg-key";
      case ToolPackagesModule tpm -> tpm.backend().id() + "-packages";
    };
  }
}
