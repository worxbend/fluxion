package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ShellRunnerTest {

  @Test
  void bufferedFallbackDeliversOutputToSink() {
    ShellRunner runner =
        (command, environment, timeout) ->
            new ProcessResult(0, "alpha\nbeta\n", "warning\n", Duration.ZERO);
    var lines = new ArrayList<String>();

    runner.run(List.of("tool"), Map.of(), Duration.ofSeconds(1), lines::add);

    assertThat(lines).containsExactly("alpha", "beta", "warning");
  }

  @Test
  void legacyWorkingDirectoryFallbackAtLeastPropagatesPwd() {
    var environments = new ArrayList<Map<String, String>>();
    ShellRunner runner =
        (command, environment, timeout) -> {
          environments.add(environment);
          return new ProcessResult(0, "", "", Duration.ZERO);
        };

    runner.run(
        List.of("tool"),
        Map.of("KEEP", "yes"),
        Optional.of(Path.of("/tmp/work")),
        Duration.ofSeconds(1));

    assertThat(environments).containsExactly(Map.of("PWD", "/tmp/work", "KEEP", "yes"));
  }
}
