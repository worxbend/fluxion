package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StepResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ItemExecutionDryRunTest {

  @Mock private InstalledProbeRegistry probes;
  @Mock private RunStateRecorder recorder;

  @Test
  void preview_skipRecorded_matchesLiveDecisionWithoutWritingState() {
    ModuleItem item = new ModuleItem(new ModuleName("tools"), "git", ItemType.PACKAGE);
    StateEntry entry =
        new StateEntry(
            "profile",
            "tools",
            "git",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty());
    var evaluator =
        new SkipEvaluator(
            Optional.of(new BootstrapState("profile", Instant.now(), "test", List.of(entry))),
            probes,
            RunStateMode.SKIP_RECORDED);
    var itemExecution = new ItemExecution(evaluator, recorder);
    var previewEvents = new ArrayList<ExecutionEvent>();
    var liveEvents = new ArrayList<ExecutionEvent>();

    itemExecution.preview(item, List.of("dnf", "install", "git"), previewEvents::add);
    itemExecution.execute(
        item, () -> new StepResult.Success("git", java.time.Duration.ZERO), liveEvents::add);

    assertThat(completed(previewEvents)).isInstanceOf(StepResult.Skipped.class);
    assertThat(completed(liveEvents)).isInstanceOf(StepResult.Skipped.class);
    verifyNoInteractions(recorder);
  }

  @Test
  void preview_liveReprobe_matchesLiveDecisionWithoutWritingState() {
    ModuleItem item = new ModuleItem(new ModuleName("tools"), "git", ItemType.PACKAGE);
    when(probes.probe(any(ModuleItem.class)))
        .thenReturn(new InstallationStatus.InstalledByProbe("git", "2.0"));
    var evaluator = new SkipEvaluator(Optional.empty(), probes, RunStateMode.LIVE_REPROBE);
    var itemExecution = new ItemExecution(evaluator, recorder);
    var previewEvents = new ArrayList<ExecutionEvent>();

    itemExecution.preview(item, List.of("dnf", "install", "git"), previewEvents::add);

    assertThat(completed(previewEvents)).isInstanceOf(StepResult.Skipped.class);
    verifyNoInteractions(recorder);
  }

  @Test
  void preview_whenLiveWouldRun_emitsDryRunWithoutWritingState() {
    ModuleItem item = new ModuleItem(new ModuleName("tools"), "git", ItemType.PACKAGE);
    when(probes.probe(item)).thenReturn(new InstallationStatus.NotInstalled("git"));
    var evaluator = new SkipEvaluator(Optional.empty(), probes, RunStateMode.LIVE_REPROBE);
    var events = new ArrayList<ExecutionEvent>();

    new ItemExecution(evaluator, recorder)
        .preview(item, List.of("dnf", "install", "git"), events::add);

    assertThat(completed(events)).isInstanceOf(StepResult.DryRun.class);
    verifyNoInteractions(recorder);
  }

  private StepResult completed(List<ExecutionEvent> events) {
    return events.stream()
        .filter(event -> event.kind() == EventKind.ITEM_COMPLETED)
        .findFirst()
        .flatMap(ExecutionEvent::result)
        .orElseThrow();
  }
}
