package dev.sysboot.cli.output;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.StepResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
              event -> Optional.of("fluxion apply --no-tui -c profile.yaml --from-phase shell"));

      listener.onEvent(ExecutionEvent.restartRequired(new PhaseName("base"), "log out"));
    } finally {
      System.setOut(originalOut);
    }

    assertThat(output.toString(StandardCharsets.UTF_8))
        .contains("[RESTART] base")
        .contains("log out")
        .contains("Resume with: fluxion apply --no-tui -c profile.yaml --from-phase shell");
  }

  @Test
  void events_whenTextContainsSecretsMarkupAndTerminalControls_printSafePlainText() {
    String rendered =
        capture(
            listener -> {
              listener.streamingOutput(true);
              listener.onEvent(
                  ExecutionEvent.itemStarted(
                      new ModuleName("commands"), "@|red ordinary-markup|@"));
              listener.onEvent(
                  ExecutionEvent.itemOutput(
                      new ModuleName("commands"),
                      "item",
                      "\u001B[31mAPI_KEY=api-value\u001B[0m "
                          + "\u001B]8;;https://evil.test\u0007link\u001B]8;;\u0007\u0000"));
              listener.onEvent(
                  ExecutionEvent.itemCompleted(
                      new ModuleName("commands"),
                      "item",
                      new StepResult.Failure(
                          "item", "PASSWORD=hunter2\u001B[2J", 1, Duration.ZERO)));
              listener.onEvent(
                  ExecutionEvent.itemCompleted(
                      new ModuleName("skip"),
                      "skip",
                      new StepResult.Skipped("skip", "MONKEY=banana passwordless=true")));
              listener.onEvent(
                  ExecutionEvent.itemCompleted(
                      new ModuleName("dry"),
                      "dry",
                      new StepResult.DryRun(
                          "dry", List.of("client", "--access-key", "access-value"))));
              listener.onEvent(
                  ExecutionEvent.itemCompleted(
                      new ModuleName("pause"),
                      "pause",
                      new StepResult.Paused(
                          "pause", "private_key=key-value\u0007", Optional.of("after-pause"), 75)));
            });

    assertThat(rendered)
        .contains(
            "@|red ordinary-markup|@",
            "API_KEY=<redacted>",
            "PASSWORD=<redacted>",
            "MONKEY=banana passwordless=true",
            "--access-key <redacted>",
            "private_key=<redacted>")
        .doesNotContain(
            "api-value",
            "hunter2",
            "access-value",
            "key-value",
            "\u001B",
            "\u0007",
            "\u0000",
            "https://evil.test");
  }

  @Test
  void restartRequired_whenResumeCommandContainsCredential_masksIt() {
    String rendered =
        capture(
            listener -> {
              var guarded =
                  new StdoutExecutionEventListener(
                      event -> Optional.of("fluxion resume --api-key resume-value"));
              guarded.onEvent(ExecutionEvent.restartRequired(new PhaseName("base"), "restart"));
            });

    assertThat(rendered)
        .contains("Resume with: fluxion resume --api-key <redacted>")
        .doesNotContain("resume-value");
  }

  @Test
  void streamedOutput_whenPrivateKeySpansEvents_masksEveryPemLine() {
    String rendered =
        capture(
            listener -> {
              listener.streamingOutput(true);
              var module = new ModuleName("commands");
              listener.onEvent(ExecutionEvent.itemStarted(module, "key"));
              listener.onEvent(
                  ExecutionEvent.itemOutput(module, "key", "-----BEGIN OPENSSH PRIVATE KEY-----"));
              listener.onEvent(
                  ExecutionEvent.itemOutput(module, "key", "c2VjcmV0LWtleS1tYXRlcmlhbA=="));
              listener.onEvent(
                  ExecutionEvent.itemOutput(module, "key", "-----END OPENSSH PRIVATE KEY-----"));
              listener.onEvent(
                  ExecutionEvent.itemCompleted(
                      module, "key", new StepResult.Success("key", Duration.ZERO)));
            });

    assertThat(rendered)
        .contains("<redacted>")
        .doesNotContain(
            "BEGIN OPENSSH PRIVATE KEY", "c2VjcmV0LWtleS1tYXRlcmlhbA==", "END OPENSSH PRIVATE KEY");
  }

  @Test
  void streamedOutput_whenPemBeginsWhileHidden_preservesMaskingAfterEnabled() {
    String rendered =
        capture(
            listener -> {
              var module = new ModuleName("commands");
              listener.onEvent(ExecutionEvent.itemStarted(module, "key"));
              listener.onEvent(
                  ExecutionEvent.itemOutput(module, "key", "-----BEGIN OPENSSH PRIVATE KEY-----"));
              listener.streamingOutput(true);
              listener.onEvent(
                  ExecutionEvent.itemOutput(module, "key", "c2VjcmV0LWtleS1tYXRlcmlhbA=="));
              listener.onEvent(
                  ExecutionEvent.itemOutput(module, "key", "-----END OPENSSH PRIVATE KEY-----"));
              listener.onEvent(
                  ExecutionEvent.itemCompleted(
                      module, "key", new StepResult.Success("key", Duration.ZERO)));
            });

    assertThat(rendered)
        .contains("<redacted>")
        .doesNotContain("c2VjcmV0LWtleS1tYXRlcmlhbA==", "END OPENSSH PRIVATE KEY");
  }

  @Test
  void streamedOutput_whenPemMarkerIsSplitAcrossEvents_masksBody() {
    String rendered =
        capture(
            listener -> {
              listener.streamingOutput(true);
              var module = new ModuleName("commands");
              listener.onEvent(ExecutionEvent.itemStarted(module, "key"));
              listener.onEvent(ExecutionEvent.itemOutput(module, "key", "-----BEGIN OPEN"));
              listener.onEvent(ExecutionEvent.itemOutput(module, "key", "SSH PRIVATE KEY-----"));
              listener.onEvent(
                  ExecutionEvent.itemOutput(module, "key", "c2VjcmV0LWtleS1tYXRlcmlhbA=="));
              listener.onEvent(
                  ExecutionEvent.itemOutput(module, "key", "-----END OPENSSH PRIVATE KEY-----"));
              listener.onEvent(
                  ExecutionEvent.itemCompleted(
                      module, "key", new StepResult.Success("key", Duration.ZERO)));
            });

    assertThat(rendered)
        .contains("<redacted>")
        .doesNotContain(
            "BEGIN OPEN",
            "SSH PRIVATE KEY",
            "c2VjcmV0LWtleS1tYXRlcmlhbA==",
            "END OPENSSH PRIVATE KEY");
  }

  private String capture(Consumer<StdoutExecutionEventListener> action) {
    var output = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
    try {
      action.accept(new StdoutExecutionEventListener());
    } finally {
      System.setOut(originalOut);
    }
    return output.toString(StandardCharsets.UTF_8);
  }
}
