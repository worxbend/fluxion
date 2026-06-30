package dev.sysboot.tui;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.SkippedPlanEntry;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ExecutionScreenState(
    String profileName,
    String currentModule,
    int totalModules,
    int completedModules,
    List<ItemStatus> items,
    List<String> logLines,
    List<String> planEntryNames,
    boolean paused,
    boolean showLogs) {

  public ExecutionScreenState {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(currentModule);
    Objects.requireNonNull(items);
    Objects.requireNonNull(logLines);
    Objects.requireNonNull(planEntryNames);
    items = List.copyOf(items);
    logLines = List.copyOf(logLines);
    planEntryNames = List.copyOf(planEntryNames);
  }

  public ExecutionScreenState(
      String profileName,
      String currentModule,
      int totalModules,
      int completedModules,
      List<ItemStatus> items,
      List<String> logLines,
      boolean paused,
      boolean showLogs) {
    this(
        profileName,
        currentModule,
        totalModules,
        completedModules,
        items,
        logLines,
        List.of(),
        paused,
        showLogs);
  }

  public static ExecutionScreenState initial(String profileName, int totalModules) {
    return new ExecutionScreenState(
        profileName, "", totalModules, 0, List.of(), List.of(), List.of(), false, false);
  }

  public static ExecutionScreenState initial(BootstrapConfig config) {
    Optional<Phase> manifestPlan = manifestPlan(config);
    if (manifestPlan.isEmpty()) {
      return initial(config.profileName().value(), config.modules().size());
    }
    List<ItemStatus> items = planItems(manifestPlan.get(), config.skippedPlanEntries());
    return new ExecutionScreenState(
        config.profileName().value(),
        "",
        manifestPlan.get().modules().size(),
        0,
        items,
        List.of(),
        planEntryNames(manifestPlan.get()),
        false,
        false);
  }

  public int progressPercent() {
    if (totalModules == 0) {
      return 0;
    }
    return (completedModules * 100) / totalModules;
  }

  public ExecutionScreenState withModule(String moduleName) {
    return new ExecutionScreenState(
        profileName,
        moduleName,
        totalModules,
        completedModules,
        items,
        logLines,
        planEntryNames,
        paused,
        showLogs);
  }

  public ExecutionScreenState withModuleCompleted() {
    return new ExecutionScreenState(
        profileName,
        currentModule,
        totalModules,
        completedModules + 1,
        items,
        logLines,
        planEntryNames,
        paused,
        showLogs);
  }

  public ExecutionScreenState withItem(ItemStatus item) {
    List<ItemStatus> updated = mergeItem(item);
    return new ExecutionScreenState(
        profileName,
        currentModule,
        totalModules,
        completedModules,
        updated,
        logLines,
        planEntryNames,
        paused,
        showLogs);
  }

  public ExecutionScreenState withItemIfPlanEntry(ItemStatus item) {
    if (!hasPlanEntry(item.name())) {
      return this;
    }
    return withItem(item);
  }

  public ExecutionScreenState withLogLine(String line) {
    int maxLines = 200;
    List<String> updated =
        logLines.size() >= maxLines ? logLines.subList(1, logLines.size()) : logLines;
    List<String> newLines = new java.util.ArrayList<>(updated);
    newLines.add(line);
    return new ExecutionScreenState(
        profileName,
        currentModule,
        totalModules,
        completedModules,
        items,
        List.copyOf(newLines),
        planEntryNames,
        paused,
        showLogs);
  }

  public ExecutionScreenState toggleLogs() {
    return new ExecutionScreenState(
        profileName,
        currentModule,
        totalModules,
        completedModules,
        items,
        logLines,
        planEntryNames,
        paused,
        !showLogs);
  }

  public boolean hasPlanEntry(String entryName) {
    return planEntryNames.contains(entryName);
  }

  public int selectedPlanEntries() {
    return planEntryNames.size();
  }

  private List<ItemStatus> mergeItem(ItemStatus newItem) {
    List<ItemStatus> mutable = new java.util.ArrayList<>(items);
    for (int i = 0; i < mutable.size(); i++) {
      if (mutable.get(i).name().equals(newItem.name())
          && mutable.get(i).module().equals(newItem.module())) {
        mutable.set(i, mergeDetail(mutable.get(i), newItem));
        return List.copyOf(mutable);
      }
    }
    mutable.add(newItem);
    return List.copyOf(mutable);
  }

  private ItemStatus mergeDetail(ItemStatus existing, ItemStatus replacement) {
    if (replacement.detail().isPresent() || existing.detail().isEmpty()) {
      return replacement;
    }
    return replacement.withDetail(existing.detail());
  }

  private static Optional<Phase> manifestPlan(BootstrapConfig config) {
    return config.phases().stream()
        .filter(phase -> phase.name().value().equals("manifest-plan"))
        .findFirst();
  }

  private static List<ItemStatus> planItems(Phase phase, List<SkippedPlanEntry> skippedEntries) {
    var items = new java.util.ArrayList<ItemStatus>();
    phase.modules().stream().map(ExecutionScreenState::pendingPlanItem).forEach(items::add);
    skippedEntries.stream().map(ExecutionScreenState::skippedPlanItem).forEach(items::add);
    return List.copyOf(items);
  }

  private static ItemStatus pendingPlanItem(BootstrapModule module) {
    Optional<String> detail =
        module instanceof InterruptModule ? Optional.of("interrupt") : Optional.empty();
    return new ItemStatus(
        module.name().value(), module.name().value(), ItemResult.PENDING, Optional.empty(), detail);
  }

  private static ItemStatus skippedPlanItem(SkippedPlanEntry entry) {
    return ItemStatus.skipped(
        entry.name(), entry.name(), entry.kind() + " skipped: " + entry.reason());
  }

  private static List<String> planEntryNames(Phase phase) {
    return phase.modules().stream().map(module -> module.name().value()).toList();
  }
}
