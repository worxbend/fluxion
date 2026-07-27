package dev.sysboot.tui;

import java.time.Duration;

public final class ExecutionScreen {

  private ExecutionScreen() {}

  public static String render(ExecutionScreenState state) {
    var sb = new StringBuilder();
    sb.append("sysboot - ")
        .append(state.profileName())
        .append(" [")
        .append(state.progressPercent())
        .append("%]\n");
    sb.append("Current module: ").append(state.currentModule()).append("\n\n");
    for (ItemStatus item : state.items()) {
      sb.append(
          "%-36s %-12s %s%s%n"
              .formatted(item.name(), item.result(), formatDuration(item), formatDetail(item)));
    }
    return sb.toString();
  }

  private static String formatDuration(ItemStatus item) {
    return item.elapsed().map(ExecutionScreen::formatDuration).orElse("");
  }

  private static String formatDuration(Duration duration) {
    return "%.1fs".formatted(duration.toMillis() / 1000.0);
  }

  private static String formatDetail(ItemStatus item) {
    return item.detail().map(detail -> "  " + detail).orElse("");
  }
}
