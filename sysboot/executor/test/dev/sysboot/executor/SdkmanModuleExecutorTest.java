package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.SdkmanModule;
import dev.sysboot.core.SdkmanPackage;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SdkmanModuleExecutorTest {

  @Test
  void dryRun_previewsSdkmanInstallCommandsWithoutRunningShell() {
    var runner = new RecordingRunner();
    var executor = new SdkmanModuleExecutor(runner);
    var events = new ArrayList<ExecutionEvent>();

    executor.dryRun(module(true, pkg("java", "25.0.1-tem"), pkg("gradle")), events::add);

    assertThat(runner.commands).isEmpty();
    assertThat(completedItems(events)).containsExactly("java@25.0.1-tem", "gradle");
    assertDryRun(events, 1, "sdk install java 25.0.1-tem");
    assertDryRun(events, 3, "sdk install gradle");
  }

  @Test
  void execute_whenMiddleItemFails_attemptsLaterItemAndRecordsSuccesses() {
    var runner = new RecordingRunner();
    var executor = new SdkmanModuleExecutor(runner);
    var events = new ArrayList<ExecutionEvent>();
    var recorded = new ArrayList<StateEntry>();

    boolean failed =
        executor.execute(
            module(false, pkg("first"), pkg("broken"), pkg("last")),
            events::add,
            new ModuleExecutionContext(
                SkipEvaluator.alwaysRun(),
                (moduleName, itemKey, itemType, result) ->
                    record(recorded, moduleName, itemKey, itemType, result)));

    assertThat(failed).isTrue();
    assertThat(runner.commands).hasSize(3);
    assertThat(completedItems(events)).containsExactly("first", "broken", "last");
    assertThat(recorded).extracting(StateEntry::itemKey).containsExactly("first", "last");
  }

  @Test
  void execute_whenShellOutputContainsSecretLikeText_redactsFailure() {
    var runner =
        new RecordingRunner(
            new ProcessResult(1, "password=hunter2", " Bearer abc.def", Duration.ZERO));
    var executor = new SdkmanModuleExecutor(runner);
    var events = new ArrayList<ExecutionEvent>();

    executor.execute(
        module(false, pkg("java")),
        events::add,
        new ModuleExecutionContext(SkipEvaluator.alwaysRun(), (a, b, c, d) -> {}));

    StepResult result = events.get(1).result().orElseThrow();
    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("password=<redacted>")
        .contains("Bearer <redacted>")
        .doesNotContain("hunter2")
        .doesNotContain("abc.def");
  }

  private static void assertDryRun(List<ExecutionEvent> events, int index, String shellFragment) {
    StepResult result = events.get(index).result().orElseThrow();
    assertThat(result).isInstanceOf(StepResult.DryRun.class);
    assertThat(((StepResult.DryRun) result).wouldExecute())
        .containsExactly(
            "/bin/bash",
            "-lc",
            "source \"$HOME/.sdkman/bin/sdkman-init.sh\" && " + shellFragment);
  }

  private static List<String> completedItems(List<ExecutionEvent> events) {
    return events.stream()
        .filter(event -> event.kind() == EventKind.ITEM_COMPLETED)
        .map(event -> event.result().orElseThrow().item())
        .toList();
  }

  private static void record(
      ArrayList<StateEntry> recorded,
      ModuleName moduleName,
      String itemKey,
      ItemType itemType,
      StepResult result) {
    if (result instanceof StepResult.Success) {
      recorded.add(
          new StateEntry(
              "test",
              moduleName.value(),
              itemKey,
              itemType,
              Instant.now(),
              Optional.empty(),
              Optional.empty()));
    }
  }

  private static SdkmanPackage pkg(String candidate) {
    return new SdkmanPackage(candidate);
  }

  private static SdkmanPackage pkg(String candidate, String version) {
    return new SdkmanPackage(candidate, Optional.of(version));
  }

  private static SdkmanModule module(boolean continueOnError, SdkmanPackage... packages) {
    return new SdkmanModule(new ModuleName("sdkman-tools"), List.of(packages), continueOnError);
  }

  private static final class RecordingRunner implements ShellRunner {
    private final ArrayList<List<String>> commands = new ArrayList<>();
    private final ProcessResult fixedResult;

    private RecordingRunner() {
      this(null);
    }

    private RecordingRunner(ProcessResult fixedResult) {
      this.fixedResult = fixedResult;
    }

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      commands.add(command);
      if (fixedResult != null) {
        return fixedResult;
      }
      int exitCode = command.getLast().contains("broken") ? 1 : 0;
      return new ProcessResult(exitCode, "", "failed", Duration.ZERO);
    }
  }
}
