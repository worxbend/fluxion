package dev.sysboot.tui;

import java.util.List;
import java.util.Objects;

public record ExecutionScreenState(
    String profileName,
    String currentModule,
    int totalModules,
    int completedModules,
    List<ItemStatus> items,
    List<String> logLines,
    boolean paused,
    boolean showLogs) {

  public ExecutionScreenState {
    Objects.requireNonNull(profileName);
    Objects.requireNonNull(currentModule);
    Objects.requireNonNull(items);
    Objects.requireNonNull(logLines);
    items = List.copyOf(items);
    logLines = List.copyOf(logLines);
  }

  public static ExecutionScreenState initial(String profileName, int totalModules) {
    return new ExecutionScreenState(
        profileName, "", totalModules, 0, List.of(), List.of(), false, false);
  }

  public int progressPercent() {
    if (totalModules == 0) {
      return 0;
    }
    return (completedModules * 100) / totalModules;
  }

  public ExecutionScreenState withModule(String moduleName) {
    return new ExecutionScreenState(
        profileName, moduleName, totalModules, completedModules, items, logLines, paused, showLogs);
  }

  public ExecutionScreenState withModuleCompleted() {
    return new ExecutionScreenState(
        profileName,
        currentModule,
        totalModules,
        completedModules + 1,
        items,
        logLines,
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
        paused,
        showLogs);
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
        paused,
        !showLogs);
  }

  private List<ItemStatus> mergeItem(ItemStatus newItem) {
    List<ItemStatus> mutable = new java.util.ArrayList<>(items);
    for (int i = 0; i < mutable.size(); i++) {
      if (mutable.get(i).name().equals(newItem.name())
          && mutable.get(i).module().equals(newItem.module())) {
        mutable.set(i, newItem);
        return List.copyOf(mutable);
      }
    }
    mutable.add(newItem);
    return List.copyOf(mutable);
  }
}
