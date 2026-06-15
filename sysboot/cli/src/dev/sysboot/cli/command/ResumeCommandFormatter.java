package dev.sysboot.cli.command;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

final class ResumeCommandFormatter {

  private ResumeCommandFormatter() {}

  static String command(Path configFile, String profile, Optional<String> fromPhase) {
    var parts =
        new ArrayList<>(
            List.of(
                "fluxion",
                "apply",
                "--no-tui",
                "-c",
                configFile.toString(),
                "--profile",
                profile,
                "--skip-already-installed"));
    fromPhase.ifPresent(
        phase -> {
          parts.add("--from-phase");
          parts.add(phase);
        });
    return parts.stream().map(ResumeCommandFormatter::shellQuote).collect(Collectors.joining(" "));
  }

  private static String shellQuote(String value) {
    if (value.matches("[A-Za-z0-9_./:=@%+,-]+")) {
      return value;
    }
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }
}
