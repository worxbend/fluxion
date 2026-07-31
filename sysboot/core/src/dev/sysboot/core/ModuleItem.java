package dev.sysboot.core;

import java.util.Objects;
import java.util.Optional;

public record ModuleItem(
    ModuleName moduleName,
    String key,
    String displayName,
    ItemType itemType,
    Optional<PackageManagerKind> packageManager,
    Optional<SourceSetup> sourceSetup,
    Optional<BootstrapModule> configuredModule) {

  public ModuleItem {
    Objects.requireNonNull(moduleName, "Module name must not be null");
    Objects.requireNonNull(key, "Item key must not be null");
    Objects.requireNonNull(displayName, "Display name must not be null");
    Objects.requireNonNull(itemType, "Item type must not be null");
    packageManager = packageManager == null ? Optional.empty() : packageManager;
    sourceSetup = sourceSetup == null ? Optional.empty() : sourceSetup;
    configuredModule = configuredModule == null ? Optional.empty() : configuredModule;
  }

  public ModuleItem(
      ModuleName moduleName,
      String key,
      String displayName,
      ItemType itemType,
      Optional<PackageManagerKind> packageManager,
      Optional<SourceSetup> sourceSetup) {
    this(moduleName, key, displayName, itemType, packageManager, sourceSetup, Optional.empty());
  }

  public ModuleItem(
      ModuleName moduleName,
      String key,
      String displayName,
      ItemType itemType,
      Optional<PackageManagerKind> packageManager) {
    this(moduleName, key, displayName, itemType, packageManager, Optional.empty());
  }

  public ModuleItem(ModuleName moduleName, String key, ItemType itemType) {
    this(moduleName, key, key, itemType, Optional.empty(), Optional.empty(), Optional.empty());
  }

  public static ModuleItem packageItem(
      ModuleName moduleName, String packageName, PackageManagerKind packageManager) {
    return new ModuleItem(
        moduleName,
        packageName,
        packageName,
        ItemType.PACKAGE,
        Optional.of(packageManager),
        Optional.empty(),
        Optional.empty());
  }

  public static ModuleItem packageActionItem(
      ModuleName moduleName, String itemKey, PackageManagerAction action, PackageManagerKind kind) {
    return new ModuleItem(
        moduleName,
        itemKey,
        action.action(),
        ItemType.PACKAGE_ACTION,
        Optional.of(kind),
        Optional.empty(),
        Optional.empty());
  }

  public static ModuleItem sourceSetupItem(SourceSetup setup, String key, ItemType itemType) {
    Objects.requireNonNull(setup, "Source setup must not be null");
    return new ModuleItem(
        setup.name(),
        key,
        key,
        itemType,
        Optional.of(setup.packageManager()),
        Optional.of(setup),
        Optional.empty());
  }

  public static ModuleItem configuredModuleItem(
      BootstrapModule module, String key, String displayName, ItemType itemType) {
    Objects.requireNonNull(module, "Configured module must not be null");
    return new ModuleItem(
        module.name(),
        key,
        displayName,
        itemType,
        Optional.empty(),
        Optional.empty(),
        Optional.of(module));
  }

  public String qualifiedKey() {
    return moduleName.value() + "/" + key;
  }

  @Override
  public String toString() {
    return "ModuleItem[moduleName="
        + moduleName
        + ", key="
        + key
        + ", displayName="
        + displayName
        + ", itemType="
        + itemType
        + ", packageManager="
        + packageManager
        + "]";
  }
}
