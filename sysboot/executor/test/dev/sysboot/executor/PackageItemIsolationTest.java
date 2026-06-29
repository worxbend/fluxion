package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StepResult;
import dev.sysboot.core.SudoPasswordProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PackageItemIsolationTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("packageManagers")
  void execute_whenMiddleItemFails_attemptsLaterItem(PackageManagerCase packageManager) {
    var executor =
        new PackageModuleExecutor(
            new PackageManagerExecutorRegistry(List.of(packageManager.executor())));
    var recorded = new ArrayList<StateEntry>();
    var events = new ArrayList<ExecutionEvent>();

    boolean failed =
        executor.execute(
            module(packageManager.kind()),
            events::add,
            new ModuleExecutionContext(
                SkipEvaluator.alwaysRun(),
                (moduleName, itemKey, itemType, result) ->
                    record(recorded, moduleName, itemKey, result)));

    assertThat(failed).isTrue();
    assertThat(packageManager.shellRunner().commands).hasSize(3);
    assertThat(packageManager.shellRunner().commands)
        .extracting(command -> command.get(command.size() - 1))
        .containsExactly("first", "broken", "last");
    assertThat(events.stream().flatMap(event -> event.result().stream()).map(StepResult::item))
        .containsExactly("first", "broken", "last");
    assertThat(recorded).extracting(StateEntry::itemKey).containsExactly("first", "last");
  }

  private static Stream<Arguments> packageManagers() {
    var sudo = (SudoPasswordProvider) prompt -> Optional.empty();
    return Stream.of(
        packageManager(PackageManagerKind.APT, runner -> new AptPackageInstaller(runner, sudo)),
        packageManager(PackageManagerKind.DNF, runner -> new DnfPackageInstaller(runner, sudo)),
        packageManager(
            PackageManagerKind.PACMAN, runner -> new PacmanPackageInstaller(runner, sudo)),
        packageManager(PackageManagerKind.PARU, runner -> new ParuPackageInstaller(runner, sudo)),
        packageManager(PackageManagerKind.YAY, runner -> new YayPackageInstaller(runner, sudo)),
        packageManager(
            PackageManagerKind.ZYPPER, runner -> new ZypperPackageInstaller(runner, sudo)));
  }

  private static Arguments packageManager(
      PackageManagerKind kind,
      java.util.function.Function<ShellRunner, PackageManagerExecutor> factory) {
    var runner = new MiddleItemFailureRunner();
    return Arguments.of(new PackageManagerCase(kind, factory.apply(runner), runner));
  }

  private static PackageModule module(PackageManagerKind kind) {
    return new PackageModule(
        new ModuleName(kind.name().toLowerCase()),
        kind,
        List.of(new PackageName("first"), new PackageName("broken"), new PackageName("last")),
        false);
  }

  private static void record(
      ArrayList<StateEntry> recorded,
      ModuleName moduleName,
      String itemKey,
      StepResult result) {
    if (!(result instanceof StepResult.Success)) {
      return;
    }
    recorded.add(
        new StateEntry(
            "test",
            moduleName.value(),
            itemKey,
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty()));
  }

  private static final class MiddleItemFailureRunner implements ShellRunner {
    private final ArrayList<List<String>> commands = new ArrayList<>();

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      commands.add(command);
      int exitCode = command.contains("broken") ? 1 : 0;
      return new ProcessResult(exitCode, "", "", Duration.ZERO);
    }
  }

  private record PackageManagerCase(
      PackageManagerKind kind,
      PackageManagerExecutor executor,
      MiddleItemFailureRunner shellRunner) {

    @Override
    public String toString() {
      return kind.name();
    }
  }
}
