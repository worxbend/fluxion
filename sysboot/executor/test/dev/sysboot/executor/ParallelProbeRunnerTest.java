package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
    when(probeRegistry.probe(any(), eq(ItemType.PACKAGE)))
        .thenAnswer(inv -> new InstallationStatus.InstalledByProbe(inv.getArgument(0), null));

    var module =
        new PackageModule(
            new ModuleName("tools"),
            PackageManagerKind.DNF,
            List.of(new PackageName("git"), new PackageName("curl")),
            true);

    var runner = new ParallelProbeRunner(probeRegistry);
    Map<String, InstallationStatus> result = runner.probeAll(List.of(module), ignored -> {});

    assertThat(result).containsKeys("git", "curl");
    assertThat(result.get("git")).isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @Test
  void probeAll_progressCallbackIsCalledForEachItem() {
    when(probeRegistry.probe(any(), any())).thenReturn(new InstallationStatus.NotInstalled("x"));

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
}
