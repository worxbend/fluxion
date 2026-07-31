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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
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

    ExecutionScreenState state = listener.drainInto(ExecutionScreenState.initial("profile", 1));

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

    assertThat(rendered)
        .contains("Interrupted: 1")
        .contains("Failed: 0")
        .contains("fluxion apply --skip-already-installed")
        .doesNotContain("--modules");
  }

  @Test
  void drainInto_whenLiveOutputContainsSecretsAndTerminalControls_storesSafeText() {
    var listener = new TuiExecutionEventListener().showCommandOutput(true);
    listener.onEvent(
        ExecutionEvent.itemOutput(
            new ModuleName("commands"),
            "credential-check",
            "\u001B[31mAPI_KEY=api-value\u001B[0m "
                + "\u001B]8;;https://evil.test\u0007link\u001B]8;;\u0007\u0000"));

    ExecutionScreenState state = listener.drainInto(ExecutionScreenState.initial("profile", 1));

    assertThat(state.logLines())
        .containsExactly("API_KEY=<redacted> link ")
        .allSatisfy(
            line ->
                assertThat(line)
                    .doesNotContain(
                        "api-value", "\u001B", "\u0007", "\u0000", "https://evil.test"));
  }

  @Test
  void drainInto_whenPrivateKeySpansOutputEvents_masksWholeBlock() {
    var listener = new TuiExecutionEventListener().showCommandOutput(true);
    var module = new ModuleName("commands");
    listener.onEvent(ExecutionEvent.itemStarted(module, "key"));
    listener.onEvent(
        ExecutionEvent.itemOutput(module, "key", "-----BEGIN OPENSSH PRIVATE KEY-----"));
    listener.onEvent(ExecutionEvent.itemOutput(module, "key", "c2VjcmV0LWtleS1tYXRlcmlhbA=="));
    listener.onEvent(ExecutionEvent.itemOutput(module, "key", "-----END OPENSSH PRIVATE KEY-----"));
    listener.onEvent(
        ExecutionEvent.itemCompleted(module, "key", new StepResult.Success("key", Duration.ZERO)));

    ExecutionScreenState state = listener.drainInto(ExecutionScreenState.initial("profile", 1));

    assertThat(state.logLines())
        .contains("<redacted>", "<redacted>", "<redacted>")
        .doesNotContain(
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "c2VjcmV0LWtleS1tYXRlcmlhbA==",
            "-----END OPENSSH PRIVATE KEY-----");
  }

  @Test
  void drainInto_whenPrivateKeyMarkerIsSplitAcrossOutputEvents_masksWholeBlock() {
    var listener = new TuiExecutionEventListener().showCommandOutput(true);
    var module = new ModuleName("commands");
    listener.onEvent(ExecutionEvent.itemStarted(module, "key"));
    listener.onEvent(ExecutionEvent.itemOutput(module, "key", "-----BEGIN OPEN"));
    listener.onEvent(ExecutionEvent.itemOutput(module, "key", "SSH PRIVATE KEY-----"));
    listener.onEvent(ExecutionEvent.itemOutput(module, "key", "c2VjcmV0LWtleS1tYXRlcmlhbA=="));
    listener.onEvent(ExecutionEvent.itemOutput(module, "key", "-----END OPENSSH PRIVATE KEY-----"));
    listener.onEvent(
        ExecutionEvent.itemCompleted(module, "key", new StepResult.Success("key", Duration.ZERO)));

    ExecutionScreenState state = listener.drainInto(ExecutionScreenState.initial("profile", 1));

    assertThat(state.logLines())
        .contains("<redacted>", "<redacted>", "<redacted>")
        .doesNotContain(
            "-----BEGIN OPEN",
            "SSH PRIVATE KEY-----",
            "c2VjcmV0LWtleS1tYXRlcmlhbA==",
            "-----END OPENSSH PRIVATE KEY-----");
  }

  @Test
  void onEvent_whenPemBeginsWhileOutputHidden_keepsMaskingAfterOutputIsEnabled() {
    var listener = new TuiExecutionEventListener();
    var module = new ModuleName("commands");
    listener.onEvent(ExecutionEvent.itemStarted(module, "key"));
    listener.onEvent(
        ExecutionEvent.itemOutput(module, "key", "-----BEGIN OPENSSH PRIVATE KEY-----"));
    listener.showCommandOutput(true);
    listener.onEvent(ExecutionEvent.itemOutput(module, "key", "c2VjcmV0LWtleS1tYXRlcmlhbA=="));
    listener.onEvent(ExecutionEvent.itemOutput(module, "key", "-----END OPENSSH PRIVATE KEY-----"));
    listener.onEvent(
        ExecutionEvent.itemCompleted(module, "key", new StepResult.Success("key", Duration.ZERO)));

    ExecutionScreenState state = listener.drainInto(ExecutionScreenState.initial("profile", 1));

    assertThat(state.logLines())
        .contains("<redacted>", "<redacted>")
        .doesNotContain("c2VjcmV0LWtleS1tYXRlcmlhbA==", "-----END OPENSSH PRIVATE KEY-----");
  }

  @Test
  void drainInto_whenCompletedEventsContainHostileDetails_storesSanitizedSnapshots() {
    var listener = new TuiExecutionEventListener();
    List<String> modules = List.of("success", "failure", "skipped", "dry-run", "paused");
    for (String module : modules) {
      listener.onEvent(ExecutionEvent.itemStarted(new ModuleName(module), module));
    }
    listener.onEvent(completed("success", new StepResult.Success("success", Duration.ZERO)));
    listener.onEvent(
        completed(
            "failure",
            new StepResult.Failure(
                "failure", "\u001B[31mPASSWORD=hunter2\u001B[0m", 1, Duration.ZERO)));
    listener.onEvent(
        completed("skipped", new StepResult.Skipped("skipped", "Bearer skip-secret\u0007")));
    listener.onEvent(
        completed(
            "dry-run",
            new StepResult.DryRun(
                "dry-run", List.of("client", "--api-key", "api-value", "\u001B]0;title\u0007"))));
    listener.onEvent(
        completed(
            "paused",
            new StepResult.Paused(
                "paused", "private_key=key-value\u001B[2J", Optional.of("after-pause"), 75)));

    ExecutionScreenState state = listener.drainInto(planState(modules));
    String snapshot = state.items() + "\n" + state.logLines();

    assertThat(snapshot)
        .contains(
            "PASSWORD=<redacted>",
            "Bearer <redacted>",
            "--api-key <redacted>",
            "private_key=<redacted>")
        .doesNotContain("hunter2", "skip-secret", "api-value", "key-value", "\u001B", "\u0007");
  }

  @Test
  void onEvent_whenOutputIsHidden_discardsFloodBeforeEnqueueing() {
    var listener = new TuiExecutionEventListener();
    for (int index = 0; index < TuiExecutionEventListener.EVENT_QUEUE_CAPACITY * 4; index++) {
      listener.onEvent(
          ExecutionEvent.itemOutput(new ModuleName("commands"), "item", "line-" + index));
    }

    assertThat(listener.pendingEventCount()).isZero();
    listener.onEvent(completed("commands", new StepResult.Success("commands", Duration.ZERO)));

    ExecutionScreenState state = listener.drainInto(ExecutionScreenState.initial("profile", 1));

    assertThat(state.items())
        .extracting(ItemStatus::name, ItemStatus::result)
        .containsExactly(tuple("commands", ItemResult.SUCCESS));
  }

  @Test
  void onEvent_whenVisibleOutputFloods_dropsOnlyOutputAndPreservesStructuralEvent()
      throws InterruptedException {
    var listener = new TuiExecutionEventListener().showCommandOutput(true);
    for (int index = 0; index < TuiExecutionEventListener.EVENT_QUEUE_CAPACITY * 4; index++) {
      listener.onEvent(
          ExecutionEvent.itemOutput(new ModuleName("commands"), "item", "line-" + index));
    }
    assertThat(listener.pendingEventCount())
        .isEqualTo(
            TuiExecutionEventListener.EVENT_QUEUE_CAPACITY
                - TuiExecutionEventListener.STRUCTURAL_EVENT_RESERVE);

    var started = new CountDownLatch(1);
    Thread producer =
        Thread.ofVirtual()
            .start(
                () -> {
                  started.countDown();
                  listener.onEvent(
                      completed("commands", new StepResult.Success("commands", Duration.ZERO)));
                });
    started.await();

    ExecutionScreenState state = listener.drainInto(ExecutionScreenState.initial("profile", 1));
    producer.join(2_000);
    assertThat(producer.isAlive()).isFalse();
    while (listener.hasPendingEvents()) {
      state = listener.drainInto(state);
    }

    assertThat(state.items())
        .extracting(ItemStatus::name, ItemStatus::result)
        .containsExactly(tuple("commands", ItemResult.SUCCESS));
  }

  @Test
  void onEvent_whenStructuralBackpressureIsInterrupted_returnsAndRestoresInterrupt()
      throws InterruptedException {
    var listener = new TuiExecutionEventListener();
    for (int index = 0; index < TuiExecutionEventListener.EVENT_QUEUE_CAPACITY; index++) {
      listener.onEvent(ExecutionEvent.phaseStarted(new PhaseName("phase-" + index)));
    }
    var started = new CountDownLatch(1);
    var returned = new AtomicBoolean();
    var interruptRestored = new AtomicBoolean();
    Thread producer =
        Thread.ofVirtual()
            .start(
                () -> {
                  started.countDown();
                  listener.onEvent(
                      completed("commands", new StepResult.Success("commands", Duration.ZERO)));
                  interruptRestored.set(Thread.currentThread().isInterrupted());
                  returned.set(true);
                });

    started.await();
    producer.interrupt();
    ExecutionScreenState state =
        listener.drainOneInto(ExecutionScreenState.initial("profile", 1)).orElseThrow();
    producer.join(2_000);

    assertThat(producer.isAlive()).isFalse();
    assertThat(returned).isTrue();
    assertThat(interruptRestored).isTrue();
    assertThat(listener.pendingEventCount())
        .isEqualTo(TuiExecutionEventListener.EVENT_QUEUE_CAPACITY);
    while (listener.hasPendingEvents()) {
      state = listener.drainInto(state);
    }
    assertThat(state.items())
        .extracting(ItemStatus::name, ItemStatus::result)
        .containsExactly(tuple("commands", ItemResult.SUCCESS));
  }

  @Test
  void render_whenRawStateContainsTerminalControls_sanitizesAtFinalBoundary() {
    var state =
        new ExecutionScreenState(
            "profile\n[FORGED]\u001B]0;owned\u0007",
            "module\u001B[2J",
            1,
            1,
            List.of(
                new ItemStatus(
                    "item\u001B[31m",
                    "module\u001B]8;;https://evil.test\u0007",
                    ItemResult.FAILED,
                    Optional.of(Duration.ZERO),
                    Optional.of("PASSWORD=hunter2\u0000"))),
            List.of(),
            false,
            false);

    String executing = ExecutionScreen.render(state);
    String completed = CompletedScreen.render(new AppState.Completed(state));

    assertThat(executing + completed)
        .contains("profile", "module", "item", "PASSWORD=<redacted>")
        .doesNotContain(
            "\n[FORGED]", "\u001B", "\u0007", "\u0000", "https://evil.test", "hunter2", "owned");
  }

  private ExecutionEvent completed(String module, StepResult result) {
    return ExecutionEvent.itemCompleted(new ModuleName(module), module, result);
  }

  private ExecutionScreenState planState(List<String> modules) {
    List<ItemStatus> items = modules.stream().map(name -> ItemStatus.pending(name, name)).toList();
    return new ExecutionScreenState(
        "profile", "", modules.size(), 0, items, List.of(), modules, false, false);
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
