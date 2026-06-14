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
    long skipped = counts.getOrDefault(ItemResult.SKIPPED, 0L);

    List<ItemStatus> failures =
        items.stream().filter(i -> i.result() == ItemResult.FAILED).toList();

    var sb = new StringBuilder();
    sb.append("┌─ Bootstrap Complete ───────────────────────────┐\n");
    sb.append(
        "│  ✓ Succeeded: %-4d  ✗ Failed: %-4d  ~ Skipped: %-4d │\n"
            .formatted(success, failed, skipped));
    sb.append("└────────────────────────────────────────────────┘\n");

    if (!failures.isEmpty()) {
      sb.append("\nFailed items (re-run with --modules to retry):\n");
      failures.forEach(
          f -> sb.append("  - ").append(f.name()).append(" [").append(f.module()).append("]\n"));
    }

    return sb.toString();
  }
}
