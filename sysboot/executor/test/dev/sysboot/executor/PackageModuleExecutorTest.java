package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerAction;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PackageModuleExecutorTest {

  @Mock private PackageManagerExecutor dnf;

  @Test
  void execute_installsPackagesAndRecordsSuccess() {
    when(dnf.supports(PackageManagerKind.DNF)).thenReturn(true);
    when(dnf.install(any())).thenReturn(new StepResult.Success("git", Duration.ZERO));
    var executor = new PackageModuleExecutor(new PackageManagerExecutorRegistry(List.of(dnf)));
    var recorded = new ArrayList<StateEntry>();

    boolean failed =
        executor.execute(
            module("git"),
            ignored -> {},
            new ModuleExecutionContext(
                SkipEvaluator.alwaysRun(),
                (moduleName, itemKey, itemType, result) ->
                    recorded.add(
                        new StateEntry(
                            "test",
                            moduleName.value(),
                            itemKey,
                            itemType,
                            java.time.Instant.now(),
                            Optional.empty(),
                            Optional.empty()))));

    assertThat(failed).isFalse();
    assertThat(recorded).extracting(StateEntry::itemKey).containsExactly("git");
    verify(dnf, times(1)).install(any());
  }

  @Test
  void execute_runsActionsBeforePackageInstalls() {
    var action = new PackageManagerAction("upgrade", List.of());
    when(dnf.supports(PackageManagerKind.DNF)).thenReturn(true);
    when(dnf.runAction(action)).thenReturn(new StepResult.Success("upgrade", Duration.ZERO));
    when(dnf.install(any())).thenReturn(new StepResult.Success("git", Duration.ZERO));
    var executor = new PackageModuleExecutor(new PackageManagerExecutorRegistry(List.of(dnf)));
    List<ExecutionEvent> events = new ArrayList<>();

    boolean failed =
        executor.execute(
            module(List.of(action), "git"),
            events::add,
            new ModuleExecutionContext(
                SkipEvaluator.alwaysRun(), (moduleName, itemKey, itemType, result) -> {}));

    assertThat(failed).isFalse();
    assertThat(completedItems(events)).containsExactly("action[0]", "git");
    verify(dnf, times(1)).runAction(action);
    verify(dnf, times(1)).install(any());
  }

  @Test
  void execute_whenMiddlePackageFails_attemptsLaterPackagesBeforeReportingFailure() {
    when(dnf.supports(PackageManagerKind.DNF)).thenReturn(true);
    when(dnf.install(new PackageName("git")))
        .thenReturn(new StepResult.Success("git", Duration.ZERO));
    when(dnf.install(new PackageName("broken")))
        .thenReturn(new StepResult.Failure("broken", "not found", 1, Duration.ZERO));
    when(dnf.install(new PackageName("curl")))
        .thenReturn(new StepResult.Success("curl", Duration.ZERO));
    var executor = new PackageModuleExecutor(new PackageManagerExecutorRegistry(List.of(dnf)));
    List<ExecutionEvent> events = new ArrayList<>();

    boolean failed =
        executor.execute(
            module(false, "git", "broken", "curl"),
            events::add,
            new ModuleExecutionContext(
                SkipEvaluator.alwaysRun(), (moduleName, itemKey, itemType, result) -> {}));

    assertThat(failed).isTrue();
    assertThat(completedItems(events)).containsExactly("git", "broken", "curl");
    verify(dnf).install(new PackageName("git"));
    verify(dnf).install(new PackageName("broken"));
    verify(dnf).install(new PackageName("curl"));
  }

  @Test
  void execute_whenActionFails_attemptsLaterPackagesBeforeReportingFailure() {
    var action = new PackageManagerAction("upgrade", List.of());
    when(dnf.supports(PackageManagerKind.DNF)).thenReturn(true);
    when(dnf.runAction(action))
        .thenReturn(new StepResult.Failure("upgrade", "failed", 1, Duration.ZERO));
    when(dnf.install(any())).thenReturn(new StepResult.Success("git", Duration.ZERO));
    var executor = new PackageModuleExecutor(new PackageManagerExecutorRegistry(List.of(dnf)));
    List<ExecutionEvent> events = new ArrayList<>();

    boolean failed =
        executor.execute(
            module(List.of(action), false, "git"),
            events::add,
            new ModuleExecutionContext(
                SkipEvaluator.alwaysRun(), (moduleName, itemKey, itemType, result) -> {}));

    assertThat(failed).isTrue();
    assertThat(completedItems(events)).containsExactly("action[0]", "git");
    verify(dnf).runAction(action);
    verify(dnf).install(new PackageName("git"));
  }

  @Test
  void execute_whenContinueOnErrorTrue_suppressesAggregateFailureAfterAttemptingItems() {
    when(dnf.supports(PackageManagerKind.DNF)).thenReturn(true);
    when(dnf.install(any()))
        .thenReturn(new StepResult.Success("git", Duration.ZERO))
        .thenReturn(new StepResult.Failure("broken", "not found", 1, Duration.ZERO))
        .thenReturn(new StepResult.Success("curl", Duration.ZERO));
    var executor = new PackageModuleExecutor(new PackageManagerExecutorRegistry(List.of(dnf)));

    boolean failed =
        executor.execute(
            module(true, "git", "broken", "curl"),
            ignored -> {},
            new ModuleExecutionContext(
                SkipEvaluator.alwaysRun(), (moduleName, itemKey, itemType, result) -> {}));

    assertThat(failed).isFalse();
    verify(dnf, times(3)).install(any());
  }

  @Test
  void execute_whenSkipEvaluatorSkips_doesNotInstall() {
    when(dnf.supports(PackageManagerKind.DNF)).thenReturn(true);
    var executor = new PackageModuleExecutor(new PackageManagerExecutorRegistry(List.of(dnf)));
    var state =
        BootstrapState.empty("test", "1.0.0")
            .withEntry(
                new StateEntry(
                    "test",
                    "tools",
                    "git",
                    dev.sysboot.core.ItemType.PACKAGE,
                    java.time.Instant.now(),
                    Optional.empty(),
                    Optional.empty()));
    var skipEvaluator =
        new SkipEvaluator(Optional.of(state), new InstalledProbeRegistry(List.of()), true, false);
    List<ExecutionEvent> events = new ArrayList<>();

    boolean failed =
        executor.execute(
            module("git"),
            events::add,
            new ModuleExecutionContext(
                skipEvaluator, (moduleName, itemKey, itemType, result) -> {}));

    assertThat(failed).isFalse();
    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.ITEM_COMPLETED);
    assertThat(events.get(1).result().orElseThrow()).isInstanceOf(StepResult.Skipped.class);
    verify(dnf, times(0)).install(any());
  }

  @Test
  void dryRun_usesPackageManagerCommandPreview() {
    when(dnf.supports(PackageManagerKind.DNF)).thenReturn(true);
    when(dnf.installCommand(any())).thenReturn(List.of("sudo", "dnf", "install", "-y", "git"));
    var executor = new PackageModuleExecutor(new PackageManagerExecutorRegistry(List.of(dnf)));
    List<ExecutionEvent> events = new ArrayList<>();

    executor.dryRun(module("git"), events::add);

    StepResult result = events.get(1).result().orElseThrow();
    assertThat(result).isInstanceOf(StepResult.DryRun.class);
    assertThat(((StepResult.DryRun) result).wouldExecute())
        .containsExactly("sudo", "dnf", "install", "-y", "git");
  }

  @Test
  void dryRun_emitsActionCommandsBeforePackageCommands() {
    var action = new PackageManagerAction("upgrade", List.of());
    when(dnf.supports(PackageManagerKind.DNF)).thenReturn(true);
    when(dnf.actionCommand(action)).thenReturn(List.of("sudo", "dnf", "upgrade", "-y"));
    when(dnf.installCommand(any())).thenReturn(List.of("sudo", "dnf", "install", "-y", "git"));
    var executor = new PackageModuleExecutor(new PackageManagerExecutorRegistry(List.of(dnf)));
    List<ExecutionEvent> events = new ArrayList<>();

    executor.dryRun(module(List.of(action), "git"), events::add);

    assertThat(completedItems(events)).containsExactly("action[0]", "git");
    assertDryRun(events, 1, "sudo", "dnf", "upgrade", "-y");
    assertDryRun(events, 3, "sudo", "dnf", "install", "-y", "git");
  }

  private static PackageModule module(String packageName) {
    return module(List.of(), true, packageName);
  }

  private static PackageModule module(List<PackageManagerAction> actions, String packageName) {
    return module(actions, true, packageName);
  }

  private static PackageModule module(boolean continueOnError, String... packageNames) {
    return module(List.of(), continueOnError, packageNames);
  }

  private static PackageModule module(
      List<PackageManagerAction> actions, boolean continueOnError, String... packageNames) {
    return new PackageModule(
        new ModuleName("tools"),
        PackageManagerKind.DNF,
        java.util.Arrays.stream(packageNames).map(PackageName::new).toList(),
        actions,
        continueOnError);
  }

  private static List<String> completedItems(List<ExecutionEvent> events) {
    return events.stream()
        .filter(event -> event.kind() == EventKind.ITEM_COMPLETED)
        .map(ExecutionEvent::item)
        .toList();
  }

  private static void assertDryRun(List<ExecutionEvent> events, int index, String... command) {
    StepResult result = events.get(index).result().orElseThrow();
    assertThat(result).isInstanceOf(StepResult.DryRun.class);
    assertThat(((StepResult.DryRun) result).wouldExecute()).containsExactly(command);
  }
}
