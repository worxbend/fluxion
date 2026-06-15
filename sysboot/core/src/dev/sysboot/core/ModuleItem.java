package dev.sysboot.core;

import java.util.Objects;
import java.util.Optional;

public record ModuleItem(
    ModuleName moduleName,
    String key,
    String displayName,
    ItemType itemType,
    Optional<PackageManagerKind> packageManager) {

  public ModuleItem {
    Objects.requireNonNull(moduleName, "Module name must not be null");
    Objects.requireNonNull(key, "Item key must not be null");
    Objects.requireNonNull(displayName, "Display name must not be null");
    Objects.requireNonNull(itemType, "Item type must not be null");
    packageManager = packageManager == null ? Optional.empty() : packageManager;
  }

  public ModuleItem(ModuleName moduleName, String key, ItemType itemType) {
    this(moduleName, key, key, itemType, Optional.empty());
  }

  public static ModuleItem packageItem(
      ModuleName moduleName, String packageName, PackageManagerKind packageManager) {
    return new ModuleItem(
        moduleName, packageName, packageName, ItemType.PACKAGE, Optional.of(packageManager));
  }
}
