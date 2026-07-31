package dev.sysboot.executor;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.StateEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StatusReportBuilder {

  public StatusReport build(
      ExecutionPlan plan,
      Optional<BootstrapState> state,
      Map<String, InstallationStatus> liveResults) {
    List<StatusReport.Item> items = new ArrayList<>();
    Set<ItemIdentity> configuredKeys = new LinkedHashSet<>();
    for (ExecutionPlan.Module sourceSetup : plan.sourceSetups()) {
      addConfiguredItems(sourceSetup, state, liveResults, configuredKeys, items);
    }
    for (ExecutionPlan.Phase phase : plan.phases()) {
      for (ExecutionPlan.Module module : phase.modules()) {
        addConfiguredItems(module, state, liveResults, configuredKeys, items);
      }
    }
    state.ifPresent(saved -> addStateOnlyItems(saved, configuredKeys, items));
    return new StatusReport(plan.profileName(), items, summary(items));
  }

  private void addConfiguredItems(
      ExecutionPlan.Module module,
      Optional<BootstrapState> state,
      Map<String, InstallationStatus> liveResults,
      Set<ItemIdentity> configuredKeys,
      List<StatusReport.Item> items) {
    for (ExecutionPlan.Item item : module.items()) {
      ModuleItem moduleItem = item.item();
      configuredKeys.add(ItemIdentity.of(moduleItem));
      Optional<StateEntry> stateEntry =
          state.flatMap(
              saved ->
                  saved.findEntry(
                      moduleItem.moduleName(), moduleItem.key(), moduleItem.itemType()));
      items.add(configuredItem(moduleItem, stateEntry, liveResults.get(moduleItem.qualifiedKey())));
    }
  }

  private StatusReport.Item configuredItem(
      ModuleItem item, Optional<StateEntry> stateEntry, InstallationStatus liveStatus) {
    return switch (liveStatus) {
      case InstallationStatus.InstalledByProbe live -> installedItem(item, stateEntry, live);
      case InstallationStatus.Unknown unknown -> unknownItem(item, unknown);
      case InstallationStatus.NotInstalled ignored -> missingItem(item, stateEntry);
      case InstallationStatus.InstalledFromState stateStatus ->
          stateBackedItem(item, stateEntry, stateStatus);
      case null -> missingItem(item, stateEntry);
    };
  }

  private StatusReport.Item installedItem(
      ModuleItem item, Optional<StateEntry> stateEntry, InstallationStatus.InstalledByProbe live) {
    String stateVersion = stateEntry.flatMap(StateEntry::version).orElse(null);
    String liveVersion = live.detectedVersion();
    if (versionDrift(stateVersion, liveVersion)) {
      return item(
          item,
          StatusReport.Classification.VERSION_DRIFT,
          "state version differs from live version",
          stateVersion,
          liveVersion);
    }
    return item(
        item,
        StatusReport.Classification.CONFIGURED_INSTALLED,
        "installed",
        stateVersion,
        liveVersion);
  }

  private StatusReport.Item stateBackedItem(
      ModuleItem item,
      Optional<StateEntry> stateEntry,
      InstallationStatus.InstalledFromState status) {
    String version = stateEntry.flatMap(StateEntry::version).orElse(status.version());
    return item(
        item, StatusReport.Classification.CONFIGURED_INSTALLED, "recorded in state", version, null);
  }

  private StatusReport.Item missingItem(ModuleItem item, Optional<StateEntry> stateEntry) {
    String detail = stateEntry.isPresent() ? "missing live, present in state" : "missing";
    return item(
        item,
        StatusReport.Classification.CONFIGURED_MISSING,
        detail,
        stateEntry.flatMap(StateEntry::version).orElse(null),
        null);
  }

  private StatusReport.Item unknownItem(ModuleItem item, InstallationStatus.Unknown unknown) {
    return item(item, StatusReport.Classification.UNKNOWN, unknown.reason(), null, null);
  }

  private void addStateOnlyItems(
      BootstrapState state, Set<ItemIdentity> configuredKeys, List<StatusReport.Item> items) {
    state.entries().stream()
        .filter(entry -> !configuredKeys.contains(ItemIdentity.of(entry)))
        .map(this::stateOnlyItem)
        .forEach(items::add);
  }

  private StatusReport.Item stateOnlyItem(StateEntry entry) {
    return new StatusReport.Item(
        entry.moduleName(),
        entry.itemKey(),
        entry.itemKey(),
        entry.itemType().name().toLowerCase(),
        StatusReport.Classification.STATE_ONLY,
        "recorded in state but absent from config",
        entry.version().orElse(null),
        null);
  }

  private StatusReport.Item item(
      ModuleItem item,
      StatusReport.Classification classification,
      String detail,
      String stateVersion,
      String liveVersion) {
    return new StatusReport.Item(
        item.moduleName().value(),
        item.key(),
        item.displayName(),
        item.itemType().name().toLowerCase(),
        classification,
        detail,
        stateVersion,
        liveVersion);
  }

  private boolean versionDrift(String stateVersion, String liveVersion) {
    return stateVersion != null
        && liveVersion != null
        && !stateVersion.isBlank()
        && !liveVersion.isBlank()
        && !stateVersion.equals(liveVersion);
  }

  private StatusReport.Summary summary(List<StatusReport.Item> items) {
    return new StatusReport.Summary(
        items.size(),
        count(items, StatusReport.Classification.CONFIGURED_INSTALLED),
        count(items, StatusReport.Classification.CONFIGURED_MISSING),
        count(items, StatusReport.Classification.STATE_ONLY),
        count(items, StatusReport.Classification.UNKNOWN),
        count(items, StatusReport.Classification.VERSION_DRIFT));
  }

  private int count(List<StatusReport.Item> items, StatusReport.Classification classification) {
    return (int) items.stream().filter(item -> item.classification() == classification).count();
  }

  private record ItemIdentity(String moduleName, String itemKey, dev.sysboot.core.ItemType type) {

    private static ItemIdentity of(ModuleItem item) {
      return new ItemIdentity(item.moduleName().value(), item.key(), item.itemType());
    }

    private static ItemIdentity of(StateEntry entry) {
      return new ItemIdentity(entry.moduleName(), entry.itemKey(), entry.itemType());
    }
  }
}
