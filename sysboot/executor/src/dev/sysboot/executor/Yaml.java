package dev.sysboot.executor;

import java.util.List;
import java.util.Map;

/**
 * Minimal YAML writer for the profiles Fluxion generates for other tools.
 *
 * <p>Only the shapes Fluxion emits are supported: nested maps, lists of maps, lists of scalars, and
 * scalars. The {@code executor} module deliberately has no YAML dependency — parsing lives in
 * {@code config-parser} — and adding one here to write forty lines would pull a parser into a layer
 * that has no business having one.
 */
final class Yaml {

  private Yaml() {}

  static String render(Map<String, Object> root) {
    var out = new StringBuilder();
    writeMap(out, root, 0);
    return out.toString();
  }

  private static void writeMap(StringBuilder out, Map<String, Object> map, int indent) {
    map.forEach((key, value) -> writeEntry(out, key, value, indent));
  }

  private static void writeEntry(StringBuilder out, String key, Object value, int indent) {
    String pad = " ".repeat(indent);
    switch (value) {
      case Map<?, ?> nested -> {
        out.append(pad).append(key).append(":\n");
        writeMap(out, castMap(nested), indent + 2);
      }
      case List<?> list -> {
        out.append(pad).append(key).append(":\n");
        list.forEach(item -> writeListItem(out, item, indent + 2));
      }
      case null -> out.append(pad).append(key).append(":\n");
      default -> out.append(pad).append(key).append(": ").append(scalar(value)).append('\n');
    }
  }

  private static void writeListItem(StringBuilder out, Object item, int indent) {
    String pad = " ".repeat(indent);
    if (item instanceof Map<?, ?> map) {
      var entries = castMap(map).entrySet().iterator();
      if (!entries.hasNext()) {
        out.append(pad).append("- {}\n");
        return;
      }
      var first = entries.next();
      // The first key sits on the dash line; the rest align under it.
      out.append(pad).append("- ");
      writeInline(out, first.getKey(), first.getValue(), indent + 2);
      entries.forEachRemaining(
          entry -> writeEntry(out, entry.getKey(), entry.getValue(), indent + 2));
      return;
    }
    out.append(pad).append("- ").append(scalar(item)).append('\n');
  }

  private static void writeInline(StringBuilder out, String key, Object value, int indent) {
    if (value instanceof Map<?, ?> nested) {
      out.append(key).append(":\n");
      writeMap(out, castMap(nested), indent + 2);
      return;
    }
    if (value instanceof List<?> list) {
      out.append(key).append(":\n");
      list.forEach(item -> writeListItem(out, item, indent + 2));
      return;
    }
    out.append(key).append(": ").append(scalar(value)).append('\n');
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Map<?, ?> map) {
    return (Map<String, Object>) map;
  }

  /**
   * Quotes anything that would otherwise change meaning.
   *
   * <p>{@code ${appsDir}/x} and {@code 0755} both have to survive a round trip: the first contains
   * characters a YAML parser treats specially in some positions, the second must stay a string
   * rather than becoming an octal-looking integer.
   */
  private static String scalar(Object value) {
    if (value instanceof Boolean || value instanceof Number) {
      return value.toString();
    }
    String text = value.toString();
    return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
