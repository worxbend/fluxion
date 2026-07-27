package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ModuleName;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Live output is what makes a ten-minute package upgrade bearable. The sink is ambient, so a shell
 * runner picks it up without every executor having to pass it along.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class StreamingOutputTest {

  @Test
  @Timeout(30)
  void aRunnerPicksUpTheAmbientSinkWithoutBeingToldAboutIt() {
    var lines = new CopyOnWriteArrayList<String>();
    var runner = new DefaultShellRunner();

    ExecutionOutput.withSink(
        lines::add,
        () ->
            runner.run(
                List.of("sh", "-c", "printf 'first\\nsecond\\n'"),
                Map.of(),
                Duration.ofSeconds(20)));

    assertThat(lines).containsExactly("first", "second");
  }

  @Test
  @Timeout(30)
  void outputIsDiscardedWhenNothingIsListening() {
    var runner = new DefaultShellRunner();

    var result = runner.run(List.of("sh", "-c", "echo ignored"), Map.of(), Duration.ofSeconds(20));

    assertThat(ExecutionOutput.isBound()).isFalse();
    assertThat(result.stdout()).contains("ignored");
  }

  @Test
  @Timeout(30)
  void theSinkIsScopedToOneItemAndDoesNotLeakIntoTheNext() {
    var firstItem = new CopyOnWriteArrayList<String>();
    var runner = new DefaultShellRunner();

    ExecutionOutput.withSink(
        firstItem::add,
        () -> runner.run(List.of("sh", "-c", "echo mine"), Map.of(), Duration.ofSeconds(20)));
    runner.run(List.of("sh", "-c", "echo not-mine"), Map.of(), Duration.ofSeconds(20));

    assertThat(firstItem).containsExactly("mine");
  }

  @Test
  void anOutputEventCarriesItsLine() {
    ExecutionEvent event =
        ExecutionEvent.itemOutput(new ModuleName("core-cli"), "git", "Retrieving package git");

    assertThat(event.kind()).isEqualTo(EventKind.ITEM_OUTPUT);
    assertThat(event.outputLine()).contains("Retrieving package git");
    assertThat(event.result()).isEmpty();
  }

  @Test
  void eventsThatAreNotOutputCarryNoLine() {
    assertThat(ExecutionEvent.itemStarted(new ModuleName("core-cli"), "git").outputLine())
        .isEmpty();
  }
}
