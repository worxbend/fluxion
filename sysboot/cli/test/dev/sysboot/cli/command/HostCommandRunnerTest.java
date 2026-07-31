package dev.sysboot.cli.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class HostCommandRunnerTest {

  @Test
  @Timeout(20)
  void lines_whenOutputExceedsLimit_continuesDrainingWithoutDeadlock() {
    var runner = new HostCommandRunner(Duration.ofSeconds(10), 2);

    HostCommandRunner.CommandResult result =
        runner.lines("sh", "-c", "i=0; while [ $i -lt 100000 ]; do echo line-$i; i=$((i+1)); done");

    assertThat(result.success()).isTrue();
    assertThat(result.lines()).containsExactly("line-0", "line-1");
  }

  @Test
  @Timeout(20)
  void lines_whenTimedOut_terminatesDescendants(@TempDir Path directory) throws Exception {
    Path pidFile = directory.resolve("child.pid");
    var runner = new HostCommandRunner(Duration.ofMillis(300), 10);

    HostCommandRunner.CommandResult result =
        runner.lines("sh", "-c", "sleep 30 & echo $! > " + pidFile + "; wait");

    assertThat(result.success()).isFalse();
    long pid = Long.parseLong(Files.readString(pidFile).trim());
    assertThat(diesWithin(pid, Duration.ofSeconds(10))).isTrue();
  }

  @Test
  void constructor_whenTimeoutOverflows_rejectsBeforeAnyLaunch() {
    assertThatThrownBy(() -> new HostCommandRunner(Duration.ofSeconds(Long.MAX_VALUE), 10))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("too large");
  }

  private boolean diesWithin(long pid, Duration limit) throws InterruptedException {
    long deadline = System.nanoTime() + limit.toNanos();
    while (System.nanoTime() < deadline) {
      Optional<ProcessHandle> handle = ProcessHandle.of(pid);
      if (handle.isEmpty() || !handle.orElseThrow().isAlive()) {
        return true;
      }
      Thread.sleep(50);
    }
    return false;
  }
}
