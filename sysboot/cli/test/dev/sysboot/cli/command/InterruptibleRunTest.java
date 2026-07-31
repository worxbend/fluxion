package dev.sysboot.cli.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class InterruptibleRunTest {

  @Test
  @Timeout(5)
  void awaitOrInterrupt_whenDrainExpires_interruptsActionThread() throws Exception {
    var finished = new CountDownLatch(1);
    var interrupted = new AtomicBoolean();
    Thread action =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Thread.sleep(Duration.ofSeconds(30));
                  } catch (InterruptedException e) {
                    interrupted.set(true);
                    finished.countDown();
                  }
                });

    InterruptibleRun.awaitOrInterrupt(finished, action, Duration.ofMillis(20));
    action.join();

    assertThat(interrupted).isTrue();
  }
}
