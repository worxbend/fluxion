package dev.sysboot.executor;

import dev.sysboot.core.ItemType;
import java.util.Objects;

record ProbeTarget(String itemKey, ItemType itemType, String moduleName) {

  ProbeTarget {
    Objects.requireNonNull(itemKey);
    Objects.requireNonNull(itemType);
    Objects.requireNonNull(moduleName);
  }
}
