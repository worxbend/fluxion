package dev.sysboot.core;

/**
 * Read-only check of whether an item is present on the current OS. Implementations must be
 * idempotent and complete within their stated timeout.
 */
public interface InstalledProbe {
  boolean supports(ItemType itemType);

  InstallationStatus probe(String itemKey);
}
