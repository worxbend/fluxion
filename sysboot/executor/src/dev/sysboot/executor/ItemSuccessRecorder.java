package dev.sysboot.executor;

import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.StepResult;

@FunctionalInterface
interface ItemSuccessRecorder {

  void record(ModuleName moduleName, String itemKey, ItemType itemType, StepResult result);
}
