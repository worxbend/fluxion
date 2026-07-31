package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ShellEnvironmentVariable;
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

  @Test
  void sensitiveEnvironment_isMaskedBeforeLiveOutputReachesAmbientSink() {
    var lines = new CopyOnWriteArrayList<String>();
    var environment = List.of(new ShellEnvironmentVariable("API_KEY", "live-api-value", true));

    ExecutionOutput.withSink(
        lines::add,
        () ->
            ExecutionOutput.withSensitiveEnvironment(
                environment,
                () -> {
                  ExecutionOutput.sink().accept("\u001B[31mvalue=live-api-value\u001B[0m\u0007");
                  return null;
                }));

    assertThat(lines).containsExactly("value=<redacted> ");
  }

  @Test
  void outputScope_masksPrivateKeyAcrossSeparateProcessLines() {
    var lines = new CopyOnWriteArrayList<String>();

    ExecutionOutput.withSink(
        lines::add,
        () -> {
          ExecutionOutput.sink().accept("-----BEGIN OPENSSH PRIVATE KEY-----");
          ExecutionOutput.sink().accept("c2VjcmV0LWtleS1tYXRlcmlhbA==");
          ExecutionOutput.sink().accept("-----END OPENSSH PRIVATE KEY-----");
          ExecutionOutput.sink().accept("ordinary");
        });

    assertThat(lines)
        .containsExactly("<redacted>", "<redacted>", "<redacted>", "ordinary")
        .doesNotContain("c2VjcmV0LWtleS1tYXRlcmlhbA==");
  }

  @Test
  @Timeout(30)
  void sensitiveEnvironment_masksSecretSplitAtForcedOutputChunkBoundary() {
    var lines = new CopyOnWriteArrayList<String>();
    var runner = new DefaultShellRunner();
    var environment = List.of(new ShellEnvironmentVariable("API_KEY", "hunter2", true));
    String command = "printf '%*s' 65533 '' | tr ' ' x; printf hunter2";

    ExecutionOutput.withSink(
        lines::add,
        () ->
            ExecutionOutput.withSensitiveEnvironment(
                environment,
                () -> runner.run(List.of("sh", "-c", command), Map.of(), Duration.ofSeconds(20))));

    assertThat(lines).contains("[output line truncated]");
    assertThat(String.join("", lines)).doesNotContain("hunter2", "hun", "ter2");
  }

  @Test
  @Timeout(30)
  void genericBearerSecretBeyondLongLineBoundary_isDiscardedInsteadOfLeakingContinuation() {
    var lines = new CopyOnWriteArrayList<String>();
    var runner = new DefaultShellRunner();
    String command = "printf '%*s' 65530 '' | tr ' ' x; printf 'Authorization: Bearer hunter2'";

    ExecutionOutput.withSink(
        lines::add,
        () -> runner.run(List.of("sh", "-c", command), Map.of(), Duration.ofSeconds(20)));

    assertThat(lines).contains("[output line truncated]");
    assertThat(String.join("", lines)).doesNotContain("hunter2", "ter2");
  }

  @Test
  void defaultRunnerDebugPreview_masksSeparateSensitiveOptionValue() {
    var runner = new DefaultShellRunner();

    assertThat(runner.maskSensitive(List.of("client", "--api-key", "hunter2")))
        .containsExactly("client", "--api-key", "<redacted>");
  }
}
