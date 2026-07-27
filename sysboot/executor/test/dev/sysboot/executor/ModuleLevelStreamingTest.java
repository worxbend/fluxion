package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.ShellRunner;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Streaming was originally bound only inside {@code executeItem}, which packages, Flatpak apps,
 * shell scripts and binary installs all bypass — so the long-running steps the feature exists for
 * produced no live output at all. The binding now sits at module level.
 */
class ModuleLevelStreamingTest {

  @Test
  void packageInstallsStreamTheirOutput() {
    var events = new ArrayList<ExecutionEvent>();
    ShellRunner runner =
        (command, env, timeout) -> {
          ExecutionOutput.sink().accept("Retrieving package git");
          return new ProcessResult(0, "", "", Duration.ZERO);
        };

    orchestrator(runner).execute(configWith(packages("core-cli", "git")), events::add);

    assertThat(outputLines(events))
        .as("a package install must produce live output, not silence")
        .contains("Retrieving package git");
  }

  @Test
  void theSinkIsBoundForEveryDispatchPathNotJustExecuteItem() {
    var events = new ArrayList<ExecutionEvent>();
    ShellRunner runner =
        (command, env, timeout) -> {
          assertThat(ExecutionOutput.isBound())
              .as("every module dispatch path must have an output sink in scope")
              .isTrue();
          return new ProcessResult(0, "", "", Duration.ZERO);
        };

    orchestrator(runner).execute(configWith(packages("core-cli", "git", "curl")), events::add);

    assertThat(events).isNotEmpty();
  }

  @Test
  void outputEventsAreAttributedToTheModuleThatProducedThem() {
    var events = new ArrayList<ExecutionEvent>();
    ShellRunner runner =
        (command, env, timeout) -> {
          ExecutionOutput.sink().accept("working");
          return new ProcessResult(0, "", "", Duration.ZERO);
        };

    orchestrator(runner).execute(configWith(packages("core-cli", "git")), events::add);

    assertThat(events)
        .filteredOn(event -> event.kind() == EventKind.ITEM_OUTPUT)
        .allSatisfy(event -> assertThat(event.moduleName().value()).isEqualTo("core-cli"));
  }

  private List<String> outputLines(List<ExecutionEvent> events) {
    return events.stream()
        .filter(event -> event.kind() == EventKind.ITEM_OUTPUT)
        .flatMap(event -> event.outputLine().stream())
        .toList();
  }

  private PackageModule packages(String moduleName, String... names) {
    return new PackageModule(
        new ModuleName(moduleName),
        PackageManagerKind.DNF,
        java.util.Arrays.stream(names).map(PackageName::new).toList(),
        false);
  }

  private BootstrapOrchestratorImpl orchestrator(ShellRunner runner) {
    return new BootstrapOrchestratorImpl(
        new PackageManagerExecutorRegistry(
            List.of(new DnfPackageInstaller(runner, prompt -> Optional.empty()))),
        new ShellScriptExecutor(runner),
        new CompiledBinaryInstaller(runner),
        new AptRepositoryInstaller(runner),
        new RpmRepositoryInstaller(runner),
        new PacmanRepositoryInstaller(runner),
        new FileWriteExecutor(runner),
        new FlatpakInstaller(runner),
        new FlatpakRemoteInstaller(runner),
        new DotbotExecutor(runner),
        new DefaultShellExecutor(runner),
        new OhMyZshExecutor(runner),
        new ToolchainExecutor(runner),
        new NerdFontExecutor(runner),
        new ShellReloadExecutor(runner),
        SkipEvaluator.alwaysRun(),
        Optional.empty(),
        "test",
        runner,
        new DefaultShellRunner());
  }

  private BootstrapConfig configWith(BootstrapModule... modules) {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("test"))
        .target(new OsTarget.FedoraTarget("44"))
        .addPhase(
            new Phase(
                new PhaseName("demo"),
                "",
                List.of(modules),
                List.of(),
                new RestartPolicy.None(),
                false))
        .build();
  }
}
