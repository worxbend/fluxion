package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.StateEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SkipEvaluatorTest {

  @Mock private InstalledProbeRegistry probeRegistry;

  @Test
  void evaluate_whenSkipDisabled_alwaysReturnsRun() {
    var evaluator = new SkipEvaluator(Optional.empty(), probeRegistry, false, false);
    SkipDecision decision = evaluator.evaluate("git", ItemType.PACKAGE);
    assertThat(decision).isInstanceOf(SkipDecision.Run.class);
  }

  @Test
  void evaluate_whenStateFileHasEntry_andReProbeOff_returnsSkip() {
    StateEntry entry =
        new StateEntry("profile", "core", "git", ItemType.PACKAGE, Instant.now(), "2.45.1", null);
    var state = new BootstrapState("profile", Instant.now(), "1.0.0", List.of(entry));

    var evaluator = new SkipEvaluator(Optional.of(state), probeRegistry, true, false);
    SkipDecision decision = evaluator.evaluate("git", ItemType.PACKAGE);

    assertThat(decision).isInstanceOf(SkipDecision.Skip.class);
    var skip = (SkipDecision.Skip) decision;
    assertThat(skip.reason()).isInstanceOf(InstallationStatus.InstalledFromState.class);
  }

  @Test
  void evaluate_whenReProbeOn_ignoresStateFileAndUsesProbe() {
    StateEntry entry =
        new StateEntry("profile", "core", "git", ItemType.PACKAGE, Instant.now(), "2.45.1", null);
    var state = new BootstrapState("profile", Instant.now(), "1.0.0", List.of(entry));

    when(probeRegistry.probe("git", ItemType.PACKAGE))
        .thenReturn(new InstallationStatus.NotInstalled("git"));

    var evaluator = new SkipEvaluator(Optional.of(state), probeRegistry, true, true);
    SkipDecision decision = evaluator.evaluate("git", ItemType.PACKAGE);

    assertThat(decision).isInstanceOf(SkipDecision.Run.class);
  }

  @Test
  void evaluate_whenProbeReturnsInstalled_returnsSkip() {
    when(probeRegistry.probe("git", ItemType.PACKAGE))
        .thenReturn(new InstallationStatus.InstalledByProbe("git", "2.45.1"));

    var evaluator = new SkipEvaluator(Optional.empty(), probeRegistry, true, false);
    SkipDecision decision = evaluator.evaluate("git", ItemType.PACKAGE);

    assertThat(decision).isInstanceOf(SkipDecision.Skip.class);
  }

  @Test
  void evaluate_whenProbeReturnsUnknown_returnRunFailSafe() {
    when(probeRegistry.probe("git", ItemType.PACKAGE))
        .thenReturn(new InstallationStatus.Unknown("git", "probe error"));

    var evaluator = new SkipEvaluator(Optional.empty(), probeRegistry, true, false);
    SkipDecision decision = evaluator.evaluate("git", ItemType.PACKAGE);

    assertThat(decision).isInstanceOf(SkipDecision.Run.class);
  }
}
