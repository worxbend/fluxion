package dev.sysboot.executor;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitRepoModule;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SystemSettingModule;
import dev.sysboot.core.SystemdUnitModule;
import dev.sysboot.core.ToolPackagesModule;
import dev.sysboot.core.UserGroupsModule;
import java.util.List;
import java.util.function.Function;

/** Dispatches direct modules whose canonical state identity is one item per list entry. */
final class ListModuleExecutor {

  private final ItemExecution itemExecution;

  ListModuleExecutor(ItemExecution itemExecution) {
    this.itemExecution = itemExecution;
  }

  boolean execute(
      GitConfigModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    return failed(
        module.continueOnError(),
        executeItems(
            module.name(),
            module.sortedKeys(),
            module::itemKey,
            ItemType.GIT_CONFIG,
            key -> executors.gitConfig().executeItem(module, key),
            module.continueOnError(),
            listener));
  }

  boolean execute(GitRepoModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    return failed(
        module.continueOnError(),
        executeItems(
            module.name(),
            module.repos(),
            GitRepoModule.GitRepo::destination,
            ItemType.GIT_REPO,
            executors.gitRepo()::executeItem,
            module.continueOnError(),
            listener));
  }

  boolean execute(
      SystemdUnitModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    return failed(
        module.continueOnError(),
        executeItems(
            module.name(),
            module.units(),
            SystemdUnitModule.SystemdUnit::qualifiedName,
            ItemType.SYSTEMD_UNIT,
            unit -> executors.systemdUnit().executeItem(module, unit),
            module.continueOnError(),
            listener));
  }

  boolean execute(
      ToolPackagesModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    return failed(
        module.continueOnError(),
        executeItems(
            module.name(),
            module.packages(),
            ToolPackagesModule.ToolPackage::name,
            ItemType.TOOL_PACKAGE,
            pkg -> executors.toolPackages().executeItem(module, pkg),
            module.continueOnError(),
            listener));
  }

  boolean execute(
      SystemSettingModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    return failed(
        module.continueOnError(),
        executeItems(
            module.name(),
            module.itemKeys(),
            Function.identity(),
            ItemType.SYSTEM_SETTING,
            key -> executors.systemSetting().executeItem(module, key),
            module.continueOnError(),
            listener));
  }

  boolean execute(
      UserGroupsModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    boolean anyFailed =
        executeItems(
            module.name(),
            module.groups(),
            module::itemKey,
            ItemType.USER_GROUP,
            group -> executors.userGroups().executeItem(module, group),
            module.continueOnError(),
            listener);
    if (!ExecutionCancellation.isCancelled() && (!anyFailed || module.continueOnError())) {
      emitPendingLogout(module, listener, executors);
    }
    return failed(module.continueOnError(), anyFailed);
  }

  private <T> boolean executeItems(
      ModuleName moduleName,
      List<T> items,
      Function<T, String> key,
      ItemType itemType,
      Function<T, StepResult> action,
      boolean continueOnError,
      ExecutionEventListener listener) {
    boolean anyFailed = false;
    for (T item : items) {
      if (ExecutionCancellation.isCancelled()) {
        break;
      }
      boolean itemFailed =
          itemExecution.execute(
              moduleName, key.apply(item), itemType, () -> action.apply(item), listener);
      anyFailed |= itemFailed;
      if (itemFailed && !continueOnError) {
        break;
      }
    }
    return anyFailed;
  }

  private boolean failed(boolean continueOnError, boolean anyFailed) {
    return anyFailed && !continueOnError;
  }

  private void emitPendingLogout(
      UserGroupsModule module, ExecutionEventListener listener, PhaseExecutors executors) {
    List<String> pending = executors.userGroups().groupsPendingLogout(module);
    if (!pending.isEmpty()) {
      listener.onEvent(
          ExecutionEvent.restartRequired(
              new PhaseName(module.name().value()),
              module.checkpointMessage().orElseGet(module::defaultCheckpointMessage)));
    }
  }
}
