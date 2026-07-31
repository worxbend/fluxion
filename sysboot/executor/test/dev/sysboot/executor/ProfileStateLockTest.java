package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BootstrapState;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileStateLockTest {

  @Test
  void withLock_acrossIndependentInstances_neverOverlaps(@TempDir Path directory)
      throws InterruptedException {
    var paths = new StatePaths(directory);
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    var threads = new ArrayList<Thread>();
    for (int index = 0; index < 8; index++) {
      var lock = new ProfileStateLock(paths);
      threads.add(
          Thread.ofVirtual()
              .start(
                  () ->
                      lock.withGlobalApplyLock(
                          () -> {
                            int concurrent = active.incrementAndGet();
                            maximum.accumulateAndGet(concurrent, Math::max);
                            try {
                              Thread.sleep(10);
                            } catch (InterruptedException e) {
                              Thread.currentThread().interrupt();
                            } finally {
                              active.decrementAndGet();
                            }
                            return null;
                          })));
    }
    for (Thread thread : threads) {
      thread.join();
    }

    assertThat(maximum).hasValue(1);
  }

  @Test
  void reset_waitsForActiveGlobalApplyAndNestedLockIsReentrant(@TempDir Path directory)
      throws Exception {
    var paths = new StatePaths(directory);
    var lock = new ProfileStateLock(paths);
    var repository = new JsonStateRepository(directory, new ObjectMapper());
    repository.save(BootstrapState.empty("profile", "1.0.0"));
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var resetDone = new AtomicBoolean();
    Thread apply =
        Thread.ofPlatform()
            .start(
                () ->
                    lock.withGlobalApplyLock(
                        () -> {
                          entered.countDown();
                          await(release);
                          return null;
                        }));

    assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    Thread reset =
        Thread.ofPlatform()
            .start(
                () -> {
                  repository.reset("profile");
                  resetDone.set(true);
                });
    Thread.sleep(50);

    assertThat(resetDone).isFalse();
    assertThat(repository.path("profile")).exists();
    release.countDown();
    apply.join(Duration.ofSeconds(2));
    reset.join(Duration.ofSeconds(2));

    assertThat(resetDone).isTrue();
    assertThat(repository.path("profile")).doesNotExist();
    lock.withGlobalApplyLock(
        () ->
            lock.withGlobalApplyLock(
                () -> {
                  return null;
                }));
  }

  private void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }
}
