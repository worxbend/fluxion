package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.SkipDecision;
import dev.sysboot.core.StateEntry;
import java.nio.file.Path;
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
    SkipDecision decision = evaluator.evaluate(item("git"));
    assertThat(decision).isInstanceOf(SkipDecision.Run.class);
  }

  @Test
  void evaluate_whenStateFileHasEntry_andReProbeOff_returnsSkip() {
    StateEntry entry =
        new StateEntry(
            "profile",
            "core",
            "git",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.of("2.45.1"),
            Optional.empty());
    var state = new BootstrapState("profile", Instant.now(), "1.0.0", List.of(entry));

    var evaluator = new SkipEvaluator(Optional.of(state), probeRegistry, true, false);
    SkipDecision decision = evaluator.evaluate(item("git"));

    assertThat(decision).isInstanceOf(SkipDecision.Skip.class);
    var skip = (SkipDecision.Skip) decision;
    assertThat(skip.reason()).isInstanceOf(InstallationStatus.InstalledFromState.class);
  }

  @Test
  void evaluate_whenAnotherModuleHasSameItemKey_doesNotUseItsState() {
    StateEntry entry =
        new StateEntry(
            "profile",
            "core",
            "shared",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty());
    var state = new BootstrapState("profile", Instant.now(), "1.0.0", List.of(entry));
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenReturn(new InstallationStatus.NotInstalled("shared"));
    var evaluator = new SkipEvaluator(Optional.of(state), probeRegistry, true, false);

    SkipDecision decision =
        evaluator.evaluate(new ModuleItem(new ModuleName("desktop"), "shared", ItemType.PACKAGE));

    assertThat(decision).isInstanceOf(SkipDecision.Run.class);
  }

  @Test
  void evaluate_whenReProbeOn_ignoresStateFileAndUsesProbe() {
    StateEntry entry =
        new StateEntry(
            "profile",
            "core",
            "git",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.of("2.45.1"),
            Optional.empty());
    var state = new BootstrapState("profile", Instant.now(), "1.0.0", List.of(entry));

    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenReturn(new InstallationStatus.NotInstalled("git"));

    var evaluator = new SkipEvaluator(Optional.of(state), probeRegistry, true, true);
    SkipDecision decision = evaluator.evaluate(item("git"));

    assertThat(decision).isInstanceOf(SkipDecision.Run.class);
  }

  @Test
  void evaluate_whenProbeReturnsInstalled_returnsSkip() {
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenReturn(new InstallationStatus.InstalledByProbe("git", "2.45.1"));

    var evaluator = new SkipEvaluator(Optional.empty(), probeRegistry, true, false);
    SkipDecision decision = evaluator.evaluate(item("git"));

    assertThat(decision).isInstanceOf(SkipDecision.Skip.class);
  }

  @Test
  void evaluate_whenProbeReturnsUnknown_returnRunFailSafe() {
    when(probeRegistry.probe(any(ModuleItem.class)))
        .thenReturn(new InstallationStatus.Unknown("git", "probe error"));

    var evaluator = new SkipEvaluator(Optional.empty(), probeRegistry, true, false);
    SkipDecision decision = evaluator.evaluate(item("git"));

    assertThat(decision).isInstanceOf(SkipDecision.Run.class);
  }

  @Test
  void evaluate_configuredSourceNeverSkipsFromStateWithoutValidatingLiveConfiguration() {
    Path source = Path.of("/etc/apt/sources.list.d/example.list");
    Path keyring = Path.of("/etc/apt/keyrings/example.gpg");
    String entry = "deb [signed-by=" + keyring + "] https://example.test stable main";
    var setup =
        new AptRepositorySourceSetup(
            new ModuleName("core"),
            entry,
            source,
            Optional.empty(),
            Optional.of(keyring),
            Optional.empty());
    ModuleItem item = ModuleItem.sourceSetupItem(setup, source.toString(), ItemType.APT_REPOSITORY);
    StateEntry entryState =
        new StateEntry(
            "profile",
            "core",
            source.toString(),
            ItemType.APT_REPOSITORY,
            Instant.now(),
            Optional.empty(),
            Optional.empty());
    var state = new BootstrapState("profile", Instant.now(), "1.0.0", List.of(entryState));
    when(probeRegistry.probe(item)).thenReturn(new InstallationStatus.NotInstalled(item.key()));

    SkipDecision decision =
        new SkipEvaluator(Optional.of(state), probeRegistry, true, false).evaluate(item);

    assertThat(decision).isInstanceOf(SkipDecision.Run.class);
  }

  private ModuleItem item(String key) {
    return new ModuleItem(new ModuleName("core"), key, ItemType.PACKAGE);
  }
}
