package dev.sysboot.core;

/**
 * Read-only check of whether an item is present on the current OS. Implementations must be
 * idempotent and complete within their stated timeout.
 */
public interface InstalledProbe {
  boolean supports(ItemType itemType);

  default boolean supports(ModuleItem item) {
    return supports(item.itemType());
  }

  default InstallationStatus probe(ModuleItem item) {
    return probe(item.key());
  }

  InstallationStatus probe(String itemKey);
}
