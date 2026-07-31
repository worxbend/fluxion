package dev.sysboot.executor;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BinstallerModule;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.CompiledBinaryModule;
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
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SystemSettingModule;
import dev.sysboot.core.SystemUpdateModule;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackagesModule;
import dev.sysboot.core.UserGroupsModule;
import dev.sysboot.core.ZypperModule;
import dev.sysboot.core.ZypperRepositoryModule;
import java.util.ArrayList;
import java.util.List;

/** Canonical item identity and type for execution planning, probing, and reporting. */
final class ModuleItemCatalog {

  private ModuleItemCatalog() {}

  static List<ModuleItem> items(BootstrapModule module) {
    var binding = StepBinding.find(module);
    if (binding.isPresent()) {
      return List.of(binding.orElseThrow().item(module));
    }
    return switch (module) {
      case PackageModule packages -> packageItems(packages);
      case ZypperModule zypper -> packageItems(zypper.asPackageModule());
      case SdkmanModule sdkman ->
          sdkman.packages().stream()
              .map(pkg -> new ModuleItem(sdkman.name(), pkg.itemKey(), ItemType.SDKMAN_PACKAGE))
              .toList();
      case AptRepositoryModule apt -> List.of(sourceItem(apt.asSourceSetup()));
      case RpmRepositoryModule rpm -> List.of(sourceItem(rpm.asSourceSetup()));
      case PacmanRepositoryModule pacman -> List.of(sourceItem(pacman.asSourceSetup()));
      case ZypperRepositoryModule zypper -> List.of(sourceItem(zypper.asSourceSetup()));
      case FlatpakRemoteModule flatpak -> List.of(sourceItem(flatpak.asSourceSetup()));
      case FileWriteModule files ->
          files.items().stream()
              .map(
                  item ->
                      new ModuleItem(
                          files.name(),
                          item.itemKey(),
                          item.name(),
                          ItemType.FILE_WRITE,
                          java.util.Optional.empty()))
              .toList();
      case FlatpakModule flatpak ->
          flatpak.appIds().stream()
              .map(app -> new ModuleItem(flatpak.name(), app, ItemType.FLATPAK))
              .toList();
      case ShellScriptModule scripts ->
          scripts.items().stream()
              .map(
                  item ->
                      ModuleItem.configuredModuleItem(
                          scripts, item.name(), item.key(), ItemType.SHELL_SCRIPT))
              .toList();
      case CompiledBinaryModule binary ->
          List.of(
              ModuleItem.configuredModuleItem(
                  binary,
                  binary.installPath().toString(),
                  binary.binaryName(),
                  ItemType.COMPILED_BINARY));
      case ShellCommandModule commands ->
          commands.items().stream()
              .map(item -> new ModuleItem(commands.name(), item.name(), ItemType.SHELL_COMMAND))
              .toList();
      case AssertModule assertion ->
          List.of(new ModuleItem(assertion.name(), assertion.name().value(), ItemType.ASSERT));
      case ManualModule manual ->
          List.of(new ModuleItem(manual.name(), manual.name().value(), ItemType.MANUAL));
      case InterruptModule interrupt ->
          List.of(new ModuleItem(interrupt.name(), interrupt.name().value(), ItemType.INTERRUPT));
      case UserGroupsModule groups ->
          groups.groups().stream()
              .map(
                  group ->
                      new ModuleItem(groups.name(), groups.itemKey(group), ItemType.USER_GROUP))
              .toList();
      case GitConfigModule git ->
          git.sortedKeys().stream()
              .map(key -> new ModuleItem(git.name(), git.itemKey(key), ItemType.GIT_CONFIG))
              .toList();
      case GitRepoModule git ->
          git.repos().stream()
              .map(repo -> new ModuleItem(git.name(), repo.destination(), ItemType.GIT_REPO))
              .toList();
      case SystemdUnitModule systemd ->
          systemd.units().stream()
              .map(
                  unit ->
                      new ModuleItem(systemd.name(), unit.qualifiedName(), ItemType.SYSTEMD_UNIT))
              .toList();
      case SystemSettingModule settings ->
          settings.itemKeys().stream()
              .map(key -> new ModuleItem(settings.name(), key, ItemType.SYSTEM_SETTING))
              .toList();
      case GpgKeyModule keys ->
          keys.keys().stream()
              .map(
                  key ->
                      new ModuleItem(
                          keys.name(),
                          key.itemKey(),
                          key.displayName(),
                          ItemType.GPG_KEY,
                          java.util.Optional.empty()))
              .toList();
      case ToolPackagesModule tools ->
          tools.packages().stream()
              .map(pkg -> new ModuleItem(tools.name(), pkg.name(), ItemType.TOOL_PACKAGE))
              .toList();
      case BinstallerModule ignored ->
          throw new IllegalStateException("Binstaller binding missing");
      case SystemUpdateModule ignored ->
          throw new IllegalStateException("System update binding missing");
      default -> throw new IllegalStateException("Canonical item binding missing for " + module);
    };
  }

  static ModuleItem sourceItem(dev.sysboot.core.SourceSetup setup) {
    return switch (setup) {
      case dev.sysboot.core.AptRepositorySourceSetup apt ->
          ModuleItem.sourceSetupItem(apt, apt.sourceListPath().toString(), ItemType.APT_REPOSITORY);
      case dev.sysboot.core.RpmRepositorySourceSetup rpm ->
          ModuleItem.sourceSetupItem(rpm, rpm.repoFilePath().toString(), ItemType.RPM_REPOSITORY);
      case dev.sysboot.core.ZypperRepositorySourceSetup zypper ->
          ModuleItem.sourceSetupItem(
              zypper, zypper.repoFilePath().toString(), ItemType.ZYPPER_REPOSITORY);
      case dev.sysboot.core.FlatpakRemoteSourceSetup flatpak ->
          ModuleItem.sourceSetupItem(flatpak, flatpak.remote(), ItemType.FLATPAK_REMOTE);
      case dev.sysboot.core.PacmanRepositorySourceSetup pacman ->
          ModuleItem.sourceSetupItem(pacman, pacman.repositoryName(), ItemType.PACMAN_REPOSITORY);
    };
  }

  private static List<ModuleItem> packageItems(PackageModule module) {
    var items = new ArrayList<ModuleItem>();
    for (int index = 0; index < module.actions().size(); index++) {
      var action = module.actions().get(index);
      items.add(
          ModuleItem.packageActionItem(
              module.name(), action.itemKey(index), action, module.packageManager()));
    }
    module.packages().stream()
        .map(pkg -> ModuleItem.packageItem(module.name(), pkg.value(), module.packageManager()))
        .forEach(items::add);
    return List.copyOf(items);
  }
}
