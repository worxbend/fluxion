package dev.sysboot.tui;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Renders the post-execution summary screen.
 *
 * <p>Counts outcomes by ItemResult and presents a table of failures for retry guidance.
 */
public final class CompletedScreen {

  private CompletedScreen() {}

  public static String render(AppState.Completed state) {
    ExecutionScreenState screen = state.finalScreen();
    List<ItemStatus> items = screen.items();

    Map<ItemResult, Long> counts =
        items.stream().collect(Collectors.groupingBy(ItemStatus::result, Collectors.counting()));

    long success = counts.getOrDefault(ItemResult.SUCCESS, 0L);
    long failed = counts.getOrDefault(ItemResult.FAILED, 0L);
    long interrupted = counts.getOrDefault(ItemResult.INTERRUPTED, 0L);
    long skipped = counts.getOrDefault(ItemResult.SKIPPED, 0L);
    long selected = selectedCount(screen, items);

    List<ItemStatus> failures =
        items.stream()
            .filter(i -> i.result() == ItemResult.FAILED || i.result() == ItemResult.INTERRUPTED)
            .toList();

    var sb = new StringBuilder();
    sb.append("┌─ Bootstrap Complete ───────────────────────────┐\n");
    sb.append(
        "│  Selected: %-4d  Completed: %-4d  Failed: %-4d │\n"
            .formatted(selected, success, failed));
    sb.append(
        "│  Interrupted: %-4d  Skipped: %-4d                  │\n"
            .formatted(interrupted, skipped));
    sb.append("└────────────────────────────────────────────────┘\n");

    if (!failures.isEmpty()) {
      sb.append("\nFailed items (re-run with --modules to retry):\n");
      failures.forEach(
          f -> sb.append("  - ").append(f.name()).append(" [").append(f.module()).append("]\n"));
    }

    return sb.toString();
  }

  private static long selectedCount(ExecutionScreenState screen, List<ItemStatus> items) {
    if (screen.selectedPlanEntries() > 0) {
      return screen.selectedPlanEntries();
    }
    return items.stream().filter(item -> item.result() != ItemResult.SKIPPED).count();
  }
}
