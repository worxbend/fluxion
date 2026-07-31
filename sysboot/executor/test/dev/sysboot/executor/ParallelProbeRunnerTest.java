package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ParallelProbeRunnerTest {

  @Mock private InstalledProbeRegistry probeRegistry;

  @Test
  void probeAll_emptyModuleList_returnsEmptyMap() {
    var runner = new ParallelProbeRunner(probeRegistry);
    Map<String, InstallationStatus> result = runner.probeAll(List.of(), ignored -> {});
    assertThat(result).isEmpty();
  }

  @Test
  void probeAll_withPackages_probesEachPackage() {
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenAnswer(
            inv -> {
              ModuleItem item = inv.getArgument(0);
              return new InstallationStatus.InstalledByProbe(item.key(), null);
            });

    var module =
        new PackageModule(
            new ModuleName("tools"),
            PackageManagerKind.DNF,
            List.of(new PackageName("git"), new PackageName("curl")),
            true);

    var runner = new ParallelProbeRunner(probeRegistry);
    Map<String, InstallationStatus> result = runner.probeAll(List.of(module), ignored -> {});

    assertThat(result).containsKeys("tools/git", "tools/curl");
    assertThat(result.get("tools/git")).isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @Test
  void probeAll_sameItemKeyInDifferentModules_keepsBothResults() {
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenAnswer(
            invocation ->
                new InstallationStatus.InstalledByProbe(
                    invocation.<ModuleItem>getArgument(0).key(), null));
    var base =
        new PackageModule(
            new ModuleName("base"), PackageManagerKind.DNF, List.of(new PackageName("git")), true);
    var development =
        new PackageModule(
            new ModuleName("development"),
            PackageManagerKind.DNF,
            List.of(new PackageName("git")),
            true);

    Map<String, InstallationStatus> result =
        new ParallelProbeRunner(probeRegistry).probeAll(List.of(base, development), ignored -> {});

    assertThat(result).containsOnlyKeys("base/git", "development/git");
  }

  @Test
  void probeAll_progressCallbackIsCalledForEachItem() {
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenReturn(new InstallationStatus.NotInstalled("x"));

    var module =
        new PackageModule(
            new ModuleName("tools"),
            PackageManagerKind.DNF,
            List.of(new PackageName("git"), new PackageName("curl"), new PackageName("wget")),
            true);

    var called = new AtomicInteger(0);
    var runner = new ParallelProbeRunner(probeRegistry);
    runner.probeAll(List.of(module), item -> called.incrementAndGet());

    assertThat(called.get()).isEqualTo(3);
  }

  @Test
  void probeAll_returnsUnmodifiableMap() {
    var runner = new ParallelProbeRunner(probeRegistry);
    Map<String, InstallationStatus> result = runner.probeAll(List.of(), ignored -> {});

    assertThat(result).isNotNull();
    org.junit.jupiter.api.Assertions.assertThrows(
        UnsupportedOperationException.class,
        () -> result.put("x", new InstallationStatus.NotInstalled("x")));
  }

  @Test
  void probeAll_neverExceedsConfiguredConcurrency() {
    var inFlight = new AtomicInteger();
    var maximum = new AtomicInteger();
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenAnswer(
            invocation -> {
              int active = inFlight.incrementAndGet();
              maximum.accumulateAndGet(active, Math::max);
              try {
                Thread.sleep(40);
              } finally {
                inFlight.decrementAndGet();
              }
              return new InstallationStatus.NotInstalled(
                  invocation.<ModuleItem>getArgument(0).key());
            });
    var module =
        new PackageModule(
            new ModuleName("tools"),
            PackageManagerKind.DNF,
            java.util.stream.IntStream.range(0, 12)
                .mapToObj(index -> new PackageName("pkg-" + index))
                .toList(),
            true);

    new ParallelProbeRunner(probeRegistry, 3, Duration.ofSeconds(5))
        .probeAll(List.of(module), ignored -> {});

    assertThat(maximum.get()).isLessThanOrEqualTo(3);
  }

  @Test
  void probeAll_appliesOneGlobalDeadlineAndCancelsUnfinishedTasks() {
    var release = new CountDownLatch(1);
    var interrupted = new CountDownLatch(1);
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenAnswer(
            invocation -> {
              try {
                release.await();
              } catch (InterruptedException e) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
              }
              return new InstallationStatus.NotInstalled(
                  invocation.<ModuleItem>getArgument(0).key());
            });
    var module =
        new PackageModule(
            new ModuleName("tools"),
            PackageManagerKind.DNF,
            List.of(new PackageName("one"), new PackageName("two")),
            true);

    long started = System.nanoTime();
    Map<String, InstallationStatus> result =
        new ParallelProbeRunner(probeRegistry, 1, Duration.ofMillis(100))
            .probeAll(List.of(module), ignored -> {});
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
    release.countDown();

    assertThat(elapsedMillis).isLessThan(750);
    assertThat(result)
        .hasSize(2)
        .allSatisfy(
            (key, status) -> {
              assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
              assertThat(((InstallationStatus.Unknown) status).reason())
                  .isEqualTo("Probe deadline exceeded");
            });
    try {
      assertThat(interrupted.await(1, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  @Test
  void probeAll_whenCallerInterrupted_returnsUnknownForEveryTarget() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return new InstallationStatus.NotInstalled(
                  invocation.<ModuleItem>getArgument(0).key());
            });
    var module =
        new PackageModule(
            new ModuleName("tools"),
            PackageManagerKind.DNF,
            List.of(new PackageName("one"), new PackageName("two")),
            true);
    var result = new AtomicReference<Map<String, InstallationStatus>>();
    Thread caller =
        Thread.ofPlatform()
            .start(
                () ->
                    result.set(
                        new ParallelProbeRunner(probeRegistry, 1, Duration.ofSeconds(5))
                            .probeAll(List.of(module), ignored -> {})));

    assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
    caller.interrupt();
    caller.join(Duration.ofSeconds(2));
    release.countDown();

    assertThat(result.get())
        .hasSize(2)
        .allSatisfy(
            (key, status) -> {
              assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
              assertThat(((InstallationStatus.Unknown) status).reason())
                  .isEqualTo("Probe interrupted");
            });
  }
}
