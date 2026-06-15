package dev.sysboot.cli.output;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.PhaseName;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StdoutExecutionEventListenerTest {

  @Test
  void restartRequired_printsResumeCommand() {
    var output = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
    try {
      var listener =
          new StdoutExecutionEventListener(
              event -> Optional.of("fluxion run --no-tui -c profile.yaml --from-phase shell"));

      listener.onEvent(ExecutionEvent.restartRequired(new PhaseName("base"), "log out"));
    } finally {
      System.setOut(originalOut);
    }

    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("[RESTART] base")
        .contains("log out")
        .contains("Resume with: fluxion run --no-tui -c profile.yaml --from-phase shell");
  }
}
