package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ProcessResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs({OS.LINUX, OS.MAC})
class ProcessExecutionTest {

  @Test
  @Timeout(30)
  void timeoutTerminatesAHangingProcess() {
    ProcessResult result = run(List.of("sh", "-c", "sleep 30"), Duration.ofMillis(300));

    assertThat(result.exitCode()).isEqualTo(ProcessExecution.TIMEOUT_EXIT_CODE);
    assertThat(result.stderr()).contains("timed out");
    assertThat(result.elapsed()).isLessThan(Duration.ofSeconds(20));
  }

  @Test
  @Timeout(30)
  void subSecondTimeoutsAreHonoured() {
    ProcessResult result = run(List.of("sh", "-c", "sleep 10"), Duration.ofMillis(200));

    assertThat(result.exitCode()).isEqualTo(ProcessExecution.TIMEOUT_EXIT_CODE);
    assertThat(result.elapsed()).isLessThan(Duration.ofSeconds(9));
  }

  @Test
  @Timeout(60)
  void largeOutputDoesNotDeadlock() {
    ProcessResult result =
        run(
            List.of("sh", "-c", "i=0; while [ $i -lt 60000 ]; do echo line-$i; i=$((i+1)); done"),
            Duration.ofSeconds(45));

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("line-0").contains("line-59999");
  }

  @Test
  @Timeout(30)
  void outputBeyondTheCaptureLimitIsTruncatedNotDropped() {
    var capture = new BoundedTextCapture(8, 8);
    char[] text = "0123456789abcdefghij".toCharArray();
    capture.append(text, 0, text.length);

    String captured = capture.toString();
    assertThat(captured).startsWith("01234567");
    assertThat(captured).endsWith("cdefghij");
    assertThat(captured).contains("characters omitted");
  }

  @Test
  @Timeout(30)
  void streamsEachLineToTheOutputSink() {
    var lines = new CopyOnWriteArrayList<String>();
    ProcessResult result =
        ProcessExecution.run(
            ProcessExecution.Request.of(
                    List.of("sh", "-c", "printf 'alpha\\nbeta\\ngamma\\n'"),
                    Map.of(),
                    Duration.ofSeconds(20))
                .withOutputSink(lines::add));

    assertThat(result.exitCode()).isZero();
    assertThat(lines).containsExactly("alpha", "beta", "gamma");
  }

  @Test
  @Timeout(30)
  void trailingLineWithoutNewlineIsStillEmitted() {
    var lines = new CopyOnWriteArrayList<String>();
    ProcessExecution.run(
        ProcessExecution.Request.of(
                List.of("sh", "-c", "printf 'no-newline'"), Map.of(), Duration.ofSeconds(20))
            .withOutputSink(lines::add));

    assertThat(lines).containsExactly("no-newline");
  }

  @Test
  @Timeout(30)
  void stdinIsClosedSoReadersSeeEndOfFileInsteadOfHanging() {
    ProcessResult result = run(List.of("sh", "-c", "cat"), Duration.ofSeconds(20));

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).isEmpty();
  }

  @Test
  @Timeout(30)
  void stdinIsDeliveredWhenProvided() {
    ProcessResult result =
        ProcessExecution.run(
            ProcessExecution.Request.of(
                    List.of("sh", "-c", "cat"), Map.of(), Duration.ofSeconds(20))
                .withStdin("secret-value\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

    assertThat(result.exitCode()).isZero();
    assertThat(result.stdout()).contains("secret-value");
  }

  @Test
  void processLaunchFailureStillErasesOwnedStdinBytes() {
    byte[] password = "heap-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    var request =
        ProcessExecution.Request.of(List.of("/bin/echo", "\0"), Map.of(), Duration.ofSeconds(20))
            .withStdin(password);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ProcessExecution.run(request))
        .isInstanceOf(ShellExecutionException.class);

    assertThat(password).containsOnly((byte) 0);
  }

  @Test
  @Timeout(30)
  void propagatesExitCodes() {
    assertThat(run(List.of("sh", "-c", "exit 42"), Duration.ofSeconds(20)).exitCode())
        .isEqualTo(42);
  }

  @Test
  @Timeout(30)
  void environmentEntriesReachTheChild() {
    ProcessResult result =
        ProcessExecution.run(
            ProcessExecution.Request.of(
                List.of("sh", "-c", "echo $FLUXION_TEST_VALUE"),
                Map.of("FLUXION_TEST_VALUE", "present"),
                Duration.ofSeconds(20)));

    assertThat(result.stdout().trim()).isEqualTo("present");
  }

  @Test
  @Timeout(30)
  void workingDirectoryControlsPwdAndRelativeFiles(@TempDir Path tempDir) throws Exception {
    Path first = Files.createDirectory(tempDir.resolve("first"));
    Path second = Files.createDirectory(tempDir.resolve("second"));

    ProcessResult firstResult =
        new DefaultShellRunner()
            .run(
                List.of("sh", "-c", "pwd; touch relative"),
                Map.of(),
                Optional.of(first),
                Duration.ofSeconds(10));
    ProcessResult secondResult =
        new DefaultShellRunner()
            .run(
                List.of("sh", "-c", "pwd; touch relative"),
                Map.of(),
                Optional.of(second),
                Duration.ofSeconds(10));

    assertThat(firstResult.stdout().trim()).isEqualTo(first.toString());
    assertThat(secondResult.stdout().trim()).isEqualTo(second.toString());
    assertThat(first.resolve("relative")).exists();
    assertThat(second.resolve("relative")).exists();
  }

  @Test
  void missingWorkingDirectoryFailsBeforeProcessLaunch(@TempDir Path tempDir) {
    Path missing = tempDir.resolve("missing");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new DefaultShellRunner()
                    .run(
                        List.of("sh", "-c", "true"),
                        Map.of(),
                        Optional.of(missing),
                        Duration.ofSeconds(10)))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessageContaining("Working directory does not exist");
  }

  @Test
  void timeoutOverflowIsRejectedBeforeProcessLaunch(@TempDir Path tempDir) {
    Path marker = tempDir.resolve("must-not-launch");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> run(List.of("sh", "-c", "touch " + marker), Duration.ofSeconds(Long.MAX_VALUE)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
    assertThat(marker).doesNotExist();
  }

  @Test
  @Timeout(60)
  void timeoutKillsDescendantsNotOnlyTheDirectChild() throws Exception {
    ProcessResult result =
        run(List.of("sh", "-c", "sleep 40 & echo $!; wait"), Duration.ofSeconds(1));

    assertThat(result.exitCode()).isEqualTo(ProcessExecution.TIMEOUT_EXIT_CODE);
    long descendantPid = Long.parseLong(result.stdout().trim().lines().findFirst().orElseThrow());
    assertThat(hasDiedWithin(descendantPid, Duration.ofSeconds(15)))
        .as("descendant %s should be terminated with the process tree", descendantPid)
        .isTrue();
  }

  private boolean hasDiedWithin(long pid, Duration limit) throws InterruptedException {
    long deadline = System.nanoTime() + limit.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<ProcessHandle> handle = ProcessHandle.of(pid);
      if (handle.isEmpty() || !handle.orElseThrow().isAlive()) {
        return true;
      }
      Thread.sleep(100);
    }
    return false;
  }

  private ProcessResult run(List<String> command, Duration timeout) {
    return ProcessExecution.run(ProcessExecution.Request.of(command, Map.of(), timeout));
  }
}
