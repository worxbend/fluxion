package dev.sysboot.executor;

import dev.sysboot.core.ShellRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RepositoryConfigVerifier {

  private RepositoryConfigVerifier() {}

  static Optional<List<String>> lines(ShellRunner runner, Path path, Duration timeout) {
    var result = runner.run(List.of("cat", "--", path.toString()), Map.of(), timeout);
    return result.isSuccess() ? Optional.of(result.stdout().lines().toList()) : Optional.empty();
  }

  static boolean containsExactLine(List<String> lines, String expected) {
    return lines.stream().map(String::strip).anyMatch(expected.strip()::equals);
  }

  static Map<String, String> iniSection(List<String> lines, String sectionName) {
    var values = new LinkedHashMap<String, String>();
    boolean inSection = false;
    for (String rawLine : lines) {
      String line = rawLine.strip();
      if (line.startsWith("[") && line.endsWith("]")) {
        inSection = line.equals("[" + sectionName + "]");
        continue;
      }
      if (!inSection || line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int separator = line.indexOf('=');
      if (separator > 0) {
        values.put(line.substring(0, separator).strip(), line.substring(separator + 1).strip());
      }
    }
    return Map.copyOf(values);
  }

  static List<String> sectionLines(List<String> lines, String sectionName) {
    var section = new java.util.ArrayList<String>();
    boolean inSection = false;
    for (String rawLine : lines) {
      String line = rawLine.strip();
      if (line.startsWith("[") && line.endsWith("]")) {
        if (inSection) {
          break;
        }
        inSection = line.equals("[" + sectionName + "]");
      } else if (inSection && !line.isEmpty()) {
        section.add(line);
      }
    }
    return List.copyOf(section);
  }
}
