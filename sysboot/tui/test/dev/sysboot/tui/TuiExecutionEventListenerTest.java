package dev.sysboot.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.StepResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiExecutionEventListenerTest {

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
}
