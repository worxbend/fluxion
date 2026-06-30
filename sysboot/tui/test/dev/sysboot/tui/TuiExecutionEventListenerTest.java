package dev.sysboot.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.InterruptResumeMode;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiExecutionEventListenerTest {

  @Test
  void initial_whenManifestPlan_seedsSelectedSkippedAndInterruptEntriesInOrder() {
    ExecutionScreenState state = ExecutionScreenState.initial(workstationConfig());

    assertThat(state.items())
        .extracting(ItemStatus::name, ItemStatus::result)
        .containsExactly(
            tuple("selected", ItemResult.PENDING),
            tuple("failing", ItemResult.PENDING),
            tuple("pause-login", ItemResult.PENDING),
            tuple("arch-only", ItemResult.SKIPPED));
    assertThat(state.items().get(2).detail()).contains("interrupt");
    assertThat(state.items().get(3).detail())
        .contains("dnf-packages skipped: when.distribution expected fedora");
    assertThat(state.selectedPlanEntries()).isEqualTo(3);
  }

  @Test
  void drainInto_whenManifestPlan_updatesEntryRowsWithoutPackageRows() {
    var listener = new TuiExecutionEventListener();
    var selected = new ModuleName("selected");
    var failing = new ModuleName("failing");
    var skipped = new ModuleName("arch-only");
    var interrupt = new ModuleName("pause-login");

    listener.onEvent(ExecutionEvent.moduleStarted(selected));
    listener.onEvent(ExecutionEvent.itemStarted(selected, "git"));
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            selected, "git", new StepResult.Success("git", Duration.ZERO)));
    listener.onEvent(ExecutionEvent.moduleCompleted(selected));
    listener.onEvent(ExecutionEvent.moduleStarted(failing));
    listener.onEvent(ExecutionEvent.itemStarted(failing, "bad-package"));
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            failing,
            "bad-package",
            new StepResult.Failure("bad-package", "install failed", 1, Duration.ZERO)));
    listener.onEvent(ExecutionEvent.moduleCompleted(failing));
    listener.onEvent(ExecutionEvent.itemStarted(skipped, "arch-only"));
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            skipped,
            "arch-only",
            new StepResult.Skipped("arch-only", "when.distribution expected fedora")));
    listener.onEvent(ExecutionEvent.moduleStarted(interrupt));
    listener.onEvent(ExecutionEvent.itemStarted(interrupt, "pause-login"));
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            interrupt,
            "pause-login",
            new StepResult.Paused(
                "pause-login", "Log out before continuing.", Optional.of("after-pause"), 75)));
    listener.onEvent(ExecutionEvent.moduleCompleted(interrupt));

    ExecutionScreenState state =
        listener.drainInto(ExecutionScreenState.initial(workstationConfig()));

    assertThat(state.items())
        .extracting(ItemStatus::name, ItemStatus::result)
        .containsExactly(
            tuple("selected", ItemResult.SUCCESS),
            tuple("failing", ItemResult.FAILED),
            tuple("pause-login", ItemResult.INTERRUPTED),
            tuple("arch-only", ItemResult.SKIPPED));
    assertThat(state.items()).extracting(ItemStatus::name).doesNotContain("git", "bad-package");
    assertThat(CompletedScreen.render(new AppState.Completed(state)))
        .contains("Selected: 3")
        .contains("Completed: 1")
        .contains("Failed: 1")
        .contains("Interrupted: 1")
        .contains("Skipped: 1");
  }

  @Test
  void drainInto_whenItemPaused_marksEntryInterrupted() {
    var listener = new TuiExecutionEventListener();
    var module = new ModuleName("pause-login");
    listener.onEvent(ExecutionEvent.itemStarted(module, "pause-login"));
    listener.onEvent(
        ExecutionEvent.itemCompleted(
            module,
            "pause-login",
            new StepResult.Paused(
                "pause-login", "Log out before continuing.", Optional.of("after-pause"), 75)));

    ExecutionScreenState state =
        listener.drainInto(ExecutionScreenState.initial("profile", 1));

    assertThat(state.items())
        .extracting(ItemStatus::name, ItemStatus::result)
        .containsExactly(tuple("pause-login", ItemResult.INTERRUPTED));
    assertThat(state.logLines()).anySatisfy(line -> assertThat(line).contains("[PAUSE]"));
  }

  @Test
  void completedScreen_countsInterruptedSeparatelyFromFailures() {
    var state =
        new ExecutionScreenState(
            "profile",
            "",
            2,
            2,
            List.of(
                new ItemStatus(
                    "pause-login", "pause-login", ItemResult.INTERRUPTED, Optional.empty()),
                new ItemStatus("git", "tools", ItemResult.SUCCESS, Optional.empty())),
            List.of(),
            false,
            false);

    String rendered = CompletedScreen.render(new AppState.Completed(state));

    assertThat(rendered).contains("Interrupted: 1").contains("Failed: 0");
  }

  private BootstrapConfig workstationConfig() {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("profile"))
        .target(new OsTarget.FedoraTarget("40"))
        .skippedPlanEntries(
            List.of(
                new SkippedPlanEntry(
                    "arch-only", "dnf-packages", "when.distribution expected fedora")))
        .addPhase(
            new Phase(
                new PhaseName("manifest-plan"),
                "WorkstationProfile plan",
                List.of(
                    new PackageModule(
                        new ModuleName("selected"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("git")),
                        false),
                    new PackageModule(
                        new ModuleName("failing"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("bad-package")),
                        false),
                    new InterruptModule(
                        new ModuleName("pause-login"),
                        "Log out before continuing.",
                        List.of(),
                        InterruptResumeMode.NEXT,
                        75)),
                List.of(),
                new RestartPolicy.None()))
        .build();
  }
}
