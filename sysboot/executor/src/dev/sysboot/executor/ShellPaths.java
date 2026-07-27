package dev.sysboot.executor;

import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands the shell-style paths that appear in real dotfiles configuration.
 *
 * <p>Destinations such as {@code ${ZSH_CUSTOM:-~/.oh-my-zsh/custom}} are copied straight out of
 * shell scripts, so they have to resolve the way the shell would rather than being rejected as
 * malformed.
 */
final class ShellPaths {

  private static final Pattern VARIABLE =
      Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

  private ShellPaths() {}

  static Path expand(String raw) {
    return Path.of(expandHome(expandVariables(raw)));
  }

  private static String expandVariables(String raw) {
    Matcher matcher = VARIABLE.matcher(raw);
    var result = new StringBuilder();
    while (matcher.find()) {
      String value = System.getenv(matcher.group(1));
      if (value == null || value.isBlank()) {
        value = matcher.group(2) == null ? "" : matcher.group(2);
      }
      matcher.appendReplacement(result, Matcher.quoteReplacement(value));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static String expandHome(String raw) {
    String home = System.getProperty("user.home", "");
    if (raw.equals("~")) {
      return home;
    }
    return raw.startsWith("~/") ? home + raw.substring(1) : raw;
  }
}
