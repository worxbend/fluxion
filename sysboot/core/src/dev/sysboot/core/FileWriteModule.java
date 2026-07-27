package dev.sysboot.core;

import java.util.List;
import java.util.Objects;

public record FileWriteModule(ModuleName name, List<FileWriteItem> items, boolean continueOnError)
    implements BootstrapModule {

  public FileWriteModule {
    Objects.requireNonNull(name);
    Objects.requireNonNull(items);
    items = List.copyOf(items);
    if (items.isEmpty()) {
      throw new IllegalArgumentException("File write module must contain at least one item");
    }
  }
}
