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

  private static PackageModule module(String packageName) {
    return new PackageModule(
        new ModuleName("tools"),
        PackageManagerKind.DNF,
        List.of(new PackageName(packageName)),
        true);
  }
}
