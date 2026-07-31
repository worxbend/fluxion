package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionPausedException;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.GitConfigModule;
import dev.sysboot.core.GitConfigScope;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.InstalledProbe;
import dev.sysboot.core.InterruptModule;
import dev.sysboot.core.InterruptResumeMode;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.NerdFontConfig;
import dev.sysboot.core.NerdFontModule;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.PlanEntryStatus;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.ScriptPath;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellCommandItem;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BootstrapOrchestratorImplTest {

  @Mock private PackageManagerExecutor dnfExecutor;

  @Mock private ShellScriptExecutor shellScriptExecutor;

  @Mock private CompiledBinaryInstaller binaryInstaller;

  @Mock private AptRepositoryInstaller aptRepositoryInstaller;

  @Mock private RpmRepositoryInstaller rpmRepositoryInstaller;

  @Mock private PacmanRepositoryInstaller pacmanRepositoryInstaller;

  @Mock private FlatpakInstaller flatpakInstaller;

  @Mock private FlatpakRemoteInstaller flatpakRemoteInstaller;

  private BootstrapOrchestratorImpl orchestrator;

  private SkipEvaluator alwaysRun() {
    var probeRegistry = new InstalledProbeRegistry(List.of());
    return new SkipEvaluator(Optional.empty(), probeRegistry, false, false);
  }

  @BeforeEach
  void setUp() {
    lenient().when(dnfExecutor.supports(PackageManagerKind.DNF)).thenReturn(true);
    lenient()
        .when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Success("pkg", Duration.ofMillis(100)));
    lenient()
        .when(dnfExecutor.installCommand(any()))
        .thenAnswer(invocation -> dnfInstallCommand(invocation.getArgument(0)));
    lenient()
        .when(binaryInstaller.dryRunCommand(any()))
        .thenAnswer(
            invocation ->
                new CompiledBinaryInstaller(new DefaultShellRunner())
                    .dryRunCommand(invocation.getArgument(0)));
    lenient()
        .when(shellScriptExecutor.executeItem(any()))
        .thenReturn(new StepResult.Success("script", Duration.ZERO));

    orchestrator = orchestrator(alwaysRun(), Optional.empty());
  }

  @Test
  void execute_whenSinglePackageModule_emitsModuleAndItemEvents() {
    var config =
        buildConfig(
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git"), new PackageName("curl")),
                    true)));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.execute(config, events::add);

    assertThat(events)
        .extracting(ExecutionEvent::kind)
        .containsExactly(
            EventKind.PHASE_STARTED,
            EventKind.MODULE_STARTED,
            EventKind.ITEM_STARTED,
            EventKind.ITEM_COMPLETED,
            EventKind.ITEM_STARTED,
            EventKind.ITEM_COMPLETED,
            EventKind.MODULE_COMPLETED,
            EventKind.PHASE_COMPLETED);
  }

  @Test
  void execute_whenRemoteScriptVerificationFails_emitsControlledItemAndPhaseFailureEvents() {
    ScriptDownloadClient failingDownload =
        (url, sha256) -> {
          throw new IOException("upstream detail");
        };
    shellScriptExecutor = new ShellScriptExecutor(new FailingShellRunner(), failingDownload);
    orchestrator = orchestrator(alwaysRun(), Optional.empty());
    var script =
        new ShellScriptItem(
            "remote",
            Optional.empty(),
            Optional.of(URI.create("https://example.test/install.sh?token=secret")),
            List.of(),
            Optional.empty(),
            List.of(),
            false,
            List.of(0),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Duration.ofMinutes(1),
            Optional.of(new Sha256Digest("0".repeat(64))));
    var module =
        new ShellScriptModule(
            new ModuleName("scripts"), List.of(script), Optional.empty(), false, Optional.empty());
    var phase =
        new Phase(
            new PhaseName("scripts"),
            "Scripts",
            List.of(module),
            List.of(),
            new RestartPolicy.None(),
            false);
    List<ExecutionEvent> events = new ArrayList<>();

    assertThatThrownBy(() -> orchestrator.execute(buildPhasedConfig(List.of(phase)), events::add))
        .isInstanceOf(BootstrapExecutionException.class);

    assertThat(events)
        .extracting(ExecutionEvent::kind)
        .containsSubsequence(
            EventKind.MODULE_STARTED,
            EventKind.ITEM_STARTED,
            EventKind.ITEM_COMPLETED,
            EventKind.MODULE_COMPLETED,
            EventKind.PHASE_FAILED);
    assertThat(events.stream().flatMap(event -> event.result().stream()).toList())
        .anySatisfy(
            result -> {
              assertThat(result).isInstanceOf(StepResult.Failure.class);
              assertThat(((StepResult.Failure) result).errorMessage())
                  .doesNotContain("secret", "upstream detail");
            });
  }

  @Test
  void execute_whenTwoPackages_callsInstallerTwice() {
    var config =
        buildConfig(
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git"), new PackageName("curl")),
                    true)));

    orchestrator.execute(config, ignored -> {});

    verify(dnfExecutor, times(2)).install(any());
  }

  @Test
  void execute_whenPackageFailsAndContinueOnErrorFalse_attemptsLaterItemsThenStopsPhase() {
    when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Failure("git", "not found", 1, Duration.ofMillis(100)))
        .thenReturn(new StepResult.Success("curl", Duration.ofMillis(100)));

    var config =
        buildPhasedConfig(
            List.of(
                phase(
                    "manifest-plan",
                    false,
                    List.of(),
                    new PackageModule(
                        new ModuleName("tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("git"), new PackageName("curl")),
                        false))));

    List<ExecutionEvent> events = new ArrayList<>();
    assertThatThrownBy(() -> orchestrator.execute(config, events::add))
        .isInstanceOf(BootstrapExecutionException.class);

    verify(dnfExecutor, times(2)).install(any());
    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
  }

  @Test
  void execute_whenProcessLaunchFails_recordsPhaseFailureBeforePropagating() {
    when(dnfExecutor.install(any()))
        .thenThrow(new ShellExecutionException("Failed to start process: dnf"));
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));
    var config =
        buildPhasedConfig(
            List.of(
                phase(
                    "foundation",
                    false,
                    List.of(),
                    new PackageModule(
                        new ModuleName("tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("git")),
                        false))));
    List<ExecutionEvent> events = new ArrayList<>();

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> orchestrator.execute(config, events::add))
        .isInstanceOf(ShellExecutionException.class);

    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
    assertThat(stateRepository.state().findPhaseEntry("foundation").orElseThrow().status())
        .isEqualTo(PhaseStatus.FAILED);
  }

  @Test
  void execute_whenShellModuleHasMultipleItems_usesPerItemIdentityAndStreaming() {
    var repository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    ShellRunner runner =
        (command, env, timeout) -> {
          ExecutionOutput.sink().accept("ran " + command.getLast());
          return new ProcessResult(0, "", "", Duration.ZERO);
        };
    orchestrator = orchestratorWithRunner(runner, Optional.of(repository));
    var module =
        new ShellCommandModule(
            new ModuleName("commands"),
            List.of(
                ShellCommandItem.shell("first", "echo first", "/bin/bash", Optional.empty()),
                ShellCommandItem.shell("second", "echo second", "/bin/bash", Optional.empty())),
            "/bin/bash",
            Optional.empty(),
            false,
            Optional.empty());
    List<ExecutionEvent> events = new ArrayList<>();

    orchestrator.execute(buildConfig(List.of(module)), events::add);

    assertThat(events)
        .filteredOn(event -> event.kind() == EventKind.ITEM_STARTED)
        .extracting(ExecutionEvent::item)
        .containsExactly("first", "second");
    assertThat(events)
        .filteredOn(event -> event.kind() == EventKind.ITEM_OUTPUT)
        .extracting(ExecutionEvent::item)
        .containsExactly("first", "second");
    assertThat(repository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("first", "second");
  }

  @Test
  void execute_whenGitConfigItemsSplit_failureStateSkipAndRetryStayPerItem() {
    var repository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    var failSecond = new java.util.concurrent.atomic.AtomicBoolean(true);
    List<List<String>> commands = new ArrayList<>();
    ShellRunner runner =
        (command, env, timeout) -> {
          commands.add(List.copyOf(command));
          if (command.contains("--get")) {
            return new ProcessResult(1, "", "", Duration.ZERO);
          }
          if (command.contains("b.two") && failSecond.get()) {
            return new ProcessResult(1, "", "failed", Duration.ZERO);
          }
          return new ProcessResult(0, "", "", Duration.ZERO);
        };
    var module =
        new GitConfigModule(
            new ModuleName("git"),
            GitConfigScope.GLOBAL,
            Map.of("a.one", "first", "b.two", "second"),
            false);
    var config = buildPhasedConfig(List.of(phase("git-config", false, List.of(), module)));
    orchestrator = orchestratorWithRunner(runner, Optional.of(repository));

    assertThatThrownBy(() -> orchestrator.execute(config, ignored -> {}))
        .isInstanceOf(BootstrapExecutionException.class);

    assertThat(repository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("global:a.one");

    failSecond.set(false);
    commands.clear();
    var skipRecorded =
        new SkipEvaluator(
            Optional.of(repository.state()),
            new InstalledProbeRegistry(List.of()),
            RunStateMode.SKIP_RECORDED);
    orchestrator =
        new BootstrapOrchestratorImpl(
            new PackageManagerExecutorRegistry(List.of(dnfExecutor)),
            shellScriptExecutor,
            binaryInstaller,
            new AptRepositoryInstaller(runner),
            new RpmRepositoryInstaller(runner),
            new PacmanRepositoryInstaller(runner),
            new FileWriteExecutor(runner),
            flatpakInstaller,
            new FlatpakRemoteInstaller(runner),
            new DotbotExecutor(runner),
            new DefaultShellExecutor(runner),
            new OhMyZshExecutor(runner),
            new ToolchainExecutor(runner),
            new NerdFontExecutor(runner),
            new ShellReloadExecutor(runner),
            skipRecorded,
            Optional.of(repository),
            "test",
            runner,
            dev.sysboot.core.ExecutionApproval.denyAll());
    List<ExecutionEvent> retryEvents = new ArrayList<>();

    orchestrator.execute(config, retryEvents::add);

    assertThat(retryEvents)
        .filteredOn(event -> event.kind() == EventKind.ITEM_COMPLETED)
        .extracting(ExecutionEvent::item)
        .contains("global:a.one", "global:b.two");
    assertThat(retryEvents)
        .filteredOn(
            event ->
                event.item().equals("global:a.one")
                    && event.result().filter(StepResult.Skipped.class::isInstance).isPresent())
        .hasSize(1);
    assertThat(commands).noneMatch(command -> command.contains("a.one"));
    assertThat(repository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("global:a.one", "global:b.two");

    repository.forgetItem("test", "global:a.one");

    assertThat(repository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("global:b.two");
  }

  @Test
  void execute_whenCancellationArrivesBetweenGitConfigItems_stopsBeforeTheSibling() {
    var cancellation = new CancellationSignal();
    var repository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    ShellRunner runner =
        (command, env, timeout) -> {
          if (command.contains("--get")) {
            return new ProcessResult(1, "", "", Duration.ZERO);
          }
          cancellation.cancel();
          return new ProcessResult(0, "", "", Duration.ZERO);
        };
    orchestrator = orchestratorWithRunner(runner, Optional.of(repository));
    var module =
        new GitConfigModule(
            new ModuleName("git"),
            GitConfigScope.GLOBAL,
            Map.of("a.one", "first", "b.two", "second"),
            false);
    List<ExecutionEvent> events = new ArrayList<>();

    assertThatThrownBy(
            () -> orchestrator.execute(buildConfig(List.of(module)), events::add, cancellation))
        .isInstanceOf(ExecutionCancelledException.class);

    assertThat(events)
        .filteredOn(event -> event.kind() == EventKind.ITEM_STARTED)
        .extracting(ExecutionEvent::item)
        .containsExactly("global:a.one");
    assertThat(repository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("global:a.one");
  }

  @Test
  void execute_whenScriptModuleHasMultipleItems_usesPerItemIdentityAndStreaming() {
    var repository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(shellScriptExecutor.executeItem(any()))
        .thenAnswer(
            invocation -> {
              ShellScriptItem item = invocation.getArgument(0);
              ExecutionOutput.sink().accept("ran " + item.name());
              return new StepResult.Success(item.name(), Duration.ZERO);
            });
    orchestrator = orchestrator(alwaysRun(), Optional.of(repository));
    var module =
        new ShellScriptModule(
            new ModuleName("scripts"),
            List.of(localScript("first", "./first.sh"), localScript("second", "./second.sh")),
            Optional.empty(),
            false,
            Optional.empty());
    List<ExecutionEvent> events = new ArrayList<>();

    orchestrator.execute(buildConfig(List.of(module)), events::add);

    assertThat(events)
        .filteredOn(event -> event.kind() == EventKind.ITEM_STARTED)
        .extracting(ExecutionEvent::item)
        .containsExactly("first", "second");
    assertThat(events)
        .filteredOn(event -> event.kind() == EventKind.ITEM_OUTPUT)
        .extracting(ExecutionEvent::item)
        .containsExactly("first", "second");
    assertThat(repository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("first", "second");
  }

  @Test
  void execute_whenPackageFailsAndContinueOnErrorTrue_continuesWithNextPackage() {
    when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Failure("git", "not found", 1, Duration.ofMillis(100)))
        .thenReturn(new StepResult.Success("curl", Duration.ofMillis(100)));

    var config =
        buildConfig(
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git"), new PackageName("curl")),
                    true)));

    orchestrator.execute(config, ignored -> {});

    verify(dnfExecutor, times(2)).install(any());
  }

  @Test
  void execute_whenAggregatePackageFailureDisallowsContinuation_skipsLaterEntryAfterItemsRun() {
    when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Success("git", Duration.ZERO))
        .thenReturn(new StepResult.Failure("broken", "not found", 1, Duration.ZERO))
        .thenReturn(new StepResult.Success("curl", Duration.ZERO));
    var config =
        buildPhasedConfig(
            List.of(
                phase(
                    "manifest-plan",
                    false,
                    List.of(),
                    new PackageModule(
                        new ModuleName("tools"),
                        PackageManagerKind.DNF,
                        List.of(
                            new PackageName("git"),
                            new PackageName("broken"),
                            new PackageName("curl")),
                        false),
                    new PackageModule(
                        new ModuleName("later-tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("jq")),
                        false))));

    assertThatThrownBy(() -> orchestrator.execute(config, ignored -> {}))
        .isInstanceOf(BootstrapExecutionException.class);

    verify(dnfExecutor, times(3)).install(any());
  }

  @Test
  void execute_whenAggregatePackageFailureAllowsContinuation_runsLaterEntry() {
    when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Success("git", Duration.ZERO))
        .thenReturn(new StepResult.Failure("broken", "not found", 1, Duration.ZERO))
        .thenReturn(new StepResult.Success("curl", Duration.ZERO))
        .thenReturn(new StepResult.Success("jq", Duration.ZERO));
    var config =
        buildPhasedConfig(
            List.of(
                phase(
                    "manifest-plan",
                    false,
                    List.of(),
                    new PackageModule(
                        new ModuleName("tools"),
                        PackageManagerKind.DNF,
                        List.of(
                            new PackageName("git"),
                            new PackageName("broken"),
                            new PackageName("curl")),
                        true),
                    new PackageModule(
                        new ModuleName("later-tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("jq")),
                        false))));

    orchestrator.execute(config, ignored -> {});

    verify(dnfExecutor, times(4)).install(any());
  }

  @Test
  void execute_whenFlatpakMiddleAppFails_stopsBeforeLaterApp() {
    when(flatpakInstaller.install(any(), org.mockito.ArgumentMatchers.eq("org.example.first")))
        .thenReturn(new StepResult.Success("org.example.first", Duration.ZERO));
    when(flatpakInstaller.install(any(), org.mockito.ArgumentMatchers.eq("org.example.broken")))
        .thenReturn(new StepResult.Failure("org.example.broken", "failed", 1, Duration.ZERO));
    var config =
        buildPhasedConfig(
            List.of(
                phase(
                    "manifest-plan",
                    false,
                    List.of(),
                    new FlatpakModule(
                        new ModuleName("desktop"),
                        "flathub",
                        List.of("org.example.first", "org.example.broken", "org.example.last"),
                        false))));

    List<ExecutionEvent> events = new ArrayList<>();
    assertThatThrownBy(() -> orchestrator.execute(config, events::add))
        .isInstanceOf(BootstrapExecutionException.class);

    verify(flatpakInstaller).install(any(), org.mockito.ArgumentMatchers.eq("org.example.first"));
    verify(flatpakInstaller).install(any(), org.mockito.ArgumentMatchers.eq("org.example.broken"));
    verify(flatpakInstaller, never())
        .install(any(), org.mockito.ArgumentMatchers.eq("org.example.last"));
    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
  }

  @Test
  void execute_whenPhaseAllowsModuleErrors_finishesSiblingsButBlocksDependentPhase() {
    when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Failure("git", "not found", 1, Duration.ofMillis(100)))
        .thenReturn(new StepResult.Success("jq", Duration.ofMillis(100)));

    var config =
        buildPhasedConfig(
            List.of(
                phase(
                    "foundation",
                    true,
                    List.of(),
                    new PackageModule(
                        new ModuleName("tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("git")),
                        false),
                    new PackageModule(
                        new ModuleName("sibling-tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("jq")),
                        false)),
                phase(
                    "dependent",
                    false,
                    List.of(new PhaseName("foundation")),
                    new PackageModule(
                        new ModuleName("more-tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("curl")),
                        false))));

    List<ExecutionEvent> events = new ArrayList<>();
    assertThatThrownBy(() -> orchestrator.execute(config, events::add))
        .isInstanceOf(BootstrapExecutionException.class);

    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
    assertThat(events)
        .filteredOn(e -> e.kind() == EventKind.PHASE_BLOCKED)
        .extracting(e -> e.phaseContext().orElseThrow())
        .containsExactly("dependent");
    verify(dnfExecutor, times(2)).install(any());
  }

  @Test
  void execute_whenPhaseDisallowsModuleErrors_blocksDependentPhase() {
    when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Failure("git", "not found", 1, Duration.ofMillis(100)));
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));

    var config =
        buildPhasedConfig(
            List.of(
                phase(
                    "foundation",
                    false,
                    List.of(),
                    new PackageModule(
                        new ModuleName("tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("git")),
                        false)),
                phase(
                    "dependent",
                    false,
                    List.of(new PhaseName("foundation")),
                    new PackageModule(
                        new ModuleName("more-tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("curl")),
                        false)),
                phase(
                    "transitive-dependent",
                    false,
                    List.of(new PhaseName("dependent")),
                    new PackageModule(
                        new ModuleName("last-tools"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("jq")),
                        false))));

    List<ExecutionEvent> events = new ArrayList<>();
    assertThatThrownBy(() -> orchestrator.execute(config, events::add))
        .isInstanceOf(BootstrapExecutionException.class);

    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
    assertThat(events)
        .filteredOn(e -> e.kind() == EventKind.PHASE_BLOCKED)
        .extracting(e -> e.phaseContext().orElseThrow())
        .containsExactly("dependent", "transitive-dependent");
    assertThat(stateRepository.state().findPhaseEntry("foundation").orElseThrow().reason())
        .contains("Phase stopped after a module failure");
    assertThat(stateRepository.state().findPhaseEntry("dependent").orElseThrow().reason())
        .contains("Blocked by failed phase: foundation");
    assertThat(
            stateRepository.state().findPhaseEntry("transitive-dependent").orElseThrow().reason())
        .contains("Blocked by failed phase: dependent");
    verify(dnfExecutor, times(1)).install(any());
  }

  @Test
  void dryRun_whenPackageModule_emitsDryRunResults() {
    var config =
        buildConfig(
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git")),
                    true)));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(config, events::add);

    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.ITEM_COMPLETED);
    var completedEvent =
        events.stream().filter(e -> e.kind() == EventKind.ITEM_COMPLETED).findFirst().orElseThrow();
    assertThat(completedEvent.result().orElseThrow()).isInstanceOf(StepResult.DryRun.class);
    var dryRun = (StepResult.DryRun) completedEvent.result().orElseThrow();
    assertThat(dryRun.wouldExecute()).containsExactly("sudo", "dnf", "install", "-y", "git");
  }

  @Test
  void dryRun_whenPlanEntriesSkipped_emitsSkippedBeforeSelectedItems() {
    var config =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("41"))
            .skippedPlanEntries(
                List.of(
                    new SkippedPlanEntry(
                        "arch-only", "pacman-packages", "when.distribution expected arch")))
            .addModule(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git")),
                    true))
            .build();

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(config, events::add);

    assertThat(events.stream().flatMap(event -> event.result().stream()).toList())
        .extracting(StepResult::item)
        .containsSubsequence("arch-only", "git");
    StepResult skipped =
        events.stream().flatMap(event -> event.result().stream()).findFirst().orElseThrow();
    assertThat(skipped).isInstanceOf(StepResult.Skipped.class);
    assertThat(((StepResult.Skipped) skipped).reason()).contains("when.distribution");
  }

  @Test
  void dryRun_whenSourceSetupConfigured_emitsSourceBeforePackage() {
    var config =
        buildConfig(
            List.of(dnfSourceSetup()),
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git")),
                    true)));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(config, events::add);

    assertThat(events.stream().flatMap(event -> event.result().stream()).toList())
        .extracting(StepResult::item)
        .containsSubsequence("/etc/yum.repos.d/docker.repo", "git");
  }

  @Test
  void dryRun_whenInstallerModulesConfigured_previewsWithoutExecuting() {
    var noExecutionRunner = new FailingShellRunner();
    orchestrator = orchestratorWithRunner(noExecutionRunner);
    // The orchestrator now previews through the injected executor rather than building a throwaway
    // one, so the stub has to supply the preview it is asked for.
    when(shellScriptExecutor.commandPreview(any()))
        .thenReturn(List.of("<interpreter>", "./scripts/bootstrap.sh", "--dry"));
    List<dev.sysboot.core.BootstrapModule> modules =
        List.of(
            new CompiledBinaryModule(
                new ModuleName("ripgrep-download"),
                "rg",
                new BinaryUrl(URI.create("https://example.com/ripgrep.tar.gz")),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Path.of("/usr/local/bin/rg"),
                Optional.of("ripgrep/bin/rg"),
                1,
                Optional.of("0755"),
                Optional.of(Path.of("/usr/local/bin/ripgrep")),
                false,
                Optional.empty(),
                Optional.empty()),
            new ShellScriptModule(
                new ModuleName("bootstrap-script"),
                new ScriptPath(Path.of("./scripts/bootstrap.sh")),
                List.of("--dry"),
                Optional.empty(),
                true),
            new ShellCommandModule(
                new ModuleName("git-defaults"),
                List.of(
                    dev.sysboot.core.ShellCommandItem.shell(
                        "git config --global init.defaultBranch main",
                        "/bin/bash",
                        Optional.empty())),
                "/bin/bash",
                Optional.empty(),
                false,
                Optional.empty()),
            new NerdFontModule(
                new ModuleName("developer-fonts"),
                "v1.0.5",
                "nerdfont-install",
                new NerdFontConfig(
                    "v3.4.0",
                    Path.of("/home/test/.local/share/fonts/NerdFonts"),
                    true,
                    List.of("JetBrainsMono")),
                Optional.empty()),
            new DotbotModule(
                new ModuleName("dotfiles"),
                Path.of("/home/test/.dotfiles/install.conf.yaml"),
                "v0.2.1",
                "dotbot",
                Optional.empty()));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(buildConfig(modules), events::add);

    List<List<String>> previews =
        events.stream()
            .flatMap(event -> event.result().stream())
            .filter(StepResult.DryRun.class::isInstance)
            .map(StepResult.DryRun.class::cast)
            .map(StepResult.DryRun::wouldExecute)
            .toList();
    assertThat(previews)
        .contains(
            // stripComponents cannot be represented by binstaller, so this archive stays on the
            // built-in exact-selection path.
            List.of(
                "download",
                "https://example.com/ripgrep.tar.gz",
                "->",
                "/usr/local/bin/rg",
                "extract",
                "ripgrep/bin/rg",
                "strip-components",
                "1",
                "mode",
                "0755",
                "symlink",
                "/usr/local/bin/ripgrep",
                "->",
                "/usr/local/bin/rg"),
            List.of("<interpreter>", "./scripts/bootstrap.sh", "--dry"),
            List.of("/bin/bash", "-lc", "git config --global init.defaultBranch main"),
            List.of("nerdfont-install", "--config", "<generated from profile>", "--dry-run"),
            List.of("dotbot", "-c", "/home/test/.dotfiles/install.conf.yaml", "--dry-run"));
    verify(shellScriptExecutor, never()).execute(any());
    verify(binaryInstaller, never()).install(any());
  }

  @Test
  void execute_whenBinaryChecksumMismatches_doesNotRecordInstallState(@TempDir Path tempDir)
      throws Exception {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    binaryInstaller =
        new CompiledBinaryInstaller(
            new FixedShellRunner(result(0)),
            new FakeDownloadClient(Map.of(binaryUri(), "bad".getBytes())),
            new DefaultBinaryFileSystem());
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep-download"),
            "rg",
            new BinaryUrl(binaryUri()),
            Optional.of(new Checksum("sha256", sha256("good".getBytes()))),
            tempDir.resolve("rg"),
            false);

    List<ExecutionEvent> events = new ArrayList<>();
    assertThatThrownBy(() -> orchestrator.execute(buildConfig(List.of(module)), events::add))
        .isInstanceOf(BootstrapExecutionException.class);

    assertThat(events)
        .flatExtracting(event -> event.result().stream().toList())
        .anySatisfy(result -> assertThat(result).isInstanceOf(StepResult.Failure.class));
    assertThat(stateRepository.state().entries()).isEmpty();
  }

  @Test
  void execute_whenRequiredSourceSetupFails_doesNotInstallPackages() {
    when(rpmRepositoryInstaller.addTrusted(any(), any()))
        .thenReturn(
            new StepResult.Failure("/etc/yum.repos.d/docker.repo", "failed", 1, Duration.ZERO));
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));

    assertThatThrownBy(
            () ->
                orchestrator.execute(
                    configWithSourceAndPackage(BootstrapPolicy.empty()), ignored -> {}))
        .isInstanceOf(BootstrapExecutionException.class);

    verify(dnfExecutor, never()).install(any());
    assertThat(stateRepository.state().entries()).isEmpty();
  }

  @Test
  void execute_whenSourceSetupFailsAndPolicyAllowsContinuation_stillBlocksPackages() {
    when(rpmRepositoryInstaller.addTrusted(any(), any()))
        .thenReturn(
            new StepResult.Failure("/etc/yum.repos.d/docker.repo", "failed", 1, Duration.ZERO));
    var policy = new BootstrapPolicy(Optional.empty(), Optional.of(true), Optional.empty());

    assertThatThrownBy(
            () -> orchestrator.execute(configWithSourceAndPackage(policy), ignored -> {}))
        .isInstanceOf(BootstrapExecutionException.class);

    verify(dnfExecutor, never()).install(new PackageName("git"));
  }

  @Test
  void execute_whenSourceSetupSucceeds_recordsRepositoryStateBeforePackageState() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(rpmRepositoryInstaller.addTrusted(any(), any()))
        .thenReturn(new StepResult.Success("/etc/yum.repos.d/docker.repo", Duration.ZERO));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));

    orchestrator.execute(configWithSourceAndPackage(BootstrapPolicy.empty()), ignored -> {});

    assertThat(stateRepository.state().entries())
        .extracting(StateEntry::itemType)
        .containsExactly(
            dev.sysboot.core.ItemType.RPM_REPOSITORY, dev.sysboot.core.ItemType.PACKAGE);
  }

  @Test
  void execute_whenCompletedPhaseFingerprintMatches_skipsPhaseModules() {
    Phase phase =
        phase(
            "foundation",
            false,
            List.of(),
            new PackageModule(
                new ModuleName("tools"),
                PackageManagerKind.DNF,
                List.of(new PackageName("git")),
                true));
    BootstrapConfig config = buildPhasedConfig(List.of(phase));
    var fingerprintCalculator = new PhaseFingerprintCalculator();
    String fingerprint = fingerprintCalculator.fingerprint(phase);
    BootstrapState state =
        BootstrapState.empty("test", "1.0.0")
            .withPhaseEntry(
                new PhaseStateEntry(
                    "foundation", PhaseStatus.COMPLETED, Instant.now(), Optional.of(fingerprint)))
            .withManifestMetadata("test", fingerprintCalculator.manifestFingerprint(config));
    var skipEvaluator =
        new SkipEvaluator(
            Optional.of(state), new InstalledProbeRegistry(List.of()), RunStateMode.SKIP_RECORDED);
    orchestrator = orchestrator(skipEvaluator, Optional.of(new InMemoryStateRepository(state)));

    orchestrator.execute(config, ignored -> {});

    verify(dnfExecutor, never()).install(any());
  }

  @Test
  void execute_whenRecordOnlyAndCompletedPhaseMatches_runsPhaseModules() {
    Phase phase =
        phase(
            "foundation",
            false,
            List.of(),
            new PackageModule(
                new ModuleName("tools"),
                PackageManagerKind.DNF,
                List.of(new PackageName("git")),
                true));
    BootstrapConfig config = buildPhasedConfig(List.of(phase));
    var fingerprints = new PhaseFingerprintCalculator();
    BootstrapState state =
        BootstrapState.empty("test", "1.0.0")
            .withPhaseEntry(
                new PhaseStateEntry(
                    "foundation",
                    PhaseStatus.COMPLETED,
                    Instant.now(),
                    Optional.of(fingerprints.fingerprint(phase))))
            .withManifestMetadata("test", fingerprints.manifestFingerprint(config));
    orchestrator = orchestrator(alwaysRun(), Optional.of(new InMemoryStateRepository(state)));

    orchestrator.execute(config, ignored -> {});

    verify(dnfExecutor).install(new PackageName("git"));
  }

  @Test
  void execute_whenRecordOnlyRunFails_removesPriorSuccessfulDecisions() {
    Phase phase = phase("foundation", false, List.of(), packages("tools", "git"));
    BootstrapConfig config = buildPhasedConfig(List.of(phase));
    var fingerprints = new PhaseFingerprintCalculator();
    BootstrapState previous =
        BootstrapState.empty("test", "1.0.0")
            .withEntry(
                new StateEntry(
                    "test",
                    "tools",
                    "git",
                    ItemType.PACKAGE,
                    Instant.now(),
                    Optional.empty(),
                    Optional.empty()))
            .withPhaseEntry(
                new PhaseStateEntry(
                    "foundation",
                    PhaseStatus.COMPLETED,
                    Instant.now(),
                    Optional.of(fingerprints.fingerprint(phase))))
            .withManifestMetadata("test", fingerprints.manifestFingerprint(config));
    var repository = new InMemoryStateRepository(previous);
    when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Failure("git", "failed", 1, Duration.ZERO));
    orchestrator = orchestrator(alwaysRun(), Optional.of(repository));

    assertThatThrownBy(() -> orchestrator.execute(config, ignored -> {}))
        .isInstanceOf(BootstrapExecutionException.class);

    assertThat(repository.state().entries()).isEmpty();
    assertThat(repository.state().findPhaseEntry("foundation").orElseThrow().status())
        .isEqualTo(PhaseStatus.FAILED);
  }

  @Test
  void execute_whenLiveReprobe_ignoresAllPersistedRunDecisions() {
    Phase phase =
        phase("foundation", false, List.of(), packages("before", "git"), packages("after", "curl"));
    BootstrapConfig config = buildPhasedConfig(List.of(phase));
    var fingerprints = new PhaseFingerprintCalculator();
    BootstrapState staleState =
        BootstrapState.empty("test", "1.0.0")
            .withPhaseEntry(
                new PhaseStateEntry(
                    "foundation",
                    PhaseStatus.COMPLETED,
                    Instant.now(),
                    Optional.of(fingerprints.fingerprint(phase))))
            .withNextPlanEntry(Optional.of("after"))
            .withManifestMetadata("test", "different-manifest");
    var repository = new InMemoryStateRepository(staleState);
    var probeCount = new AtomicInteger();
    var liveReprobe =
        new SkipEvaluator(
            Optional.of(staleState),
            new InstalledProbeRegistry(List.of(notInstalledProbe(probeCount))),
            RunStateMode.LIVE_REPROBE);
    orchestrator = orchestrator(liveReprobe, Optional.of(repository));

    orchestrator.execute(config, ignored -> {});

    verify(dnfExecutor, times(2)).install(any());
    assertThat(probeCount).hasValue(2);
    assertThat(repository.state().nextPlanEntry()).isEmpty();
    assertThat(repository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("git", "curl");
    assertThat(repository.state().manifestFingerprint())
        .contains(fingerprints.manifestFingerprint(config));
  }

  @Test
  void execute_whenManifestFingerprintDiffers_rejectsStaleState() {
    Phase oldPhase =
        phase(
            "foundation",
            false,
            List.of(),
            new PackageModule(
                new ModuleName("tools"),
                PackageManagerKind.DNF,
                List.of(new PackageName("git")),
                true));
    Phase changedPhase =
        phase(
            "foundation",
            false,
            List.of(),
            new PackageModule(
                new ModuleName("tools"),
                PackageManagerKind.DNF,
                List.of(new PackageName("git"), new PackageName("curl")),
                true));
    BootstrapConfig oldConfig = buildPhasedConfig(List.of(oldPhase));
    BootstrapConfig changedConfig = buildPhasedConfig(List.of(changedPhase));
    var fingerprintCalculator = new PhaseFingerprintCalculator();
    String oldFingerprint = fingerprintCalculator.fingerprint(oldPhase);
    BootstrapState state =
        BootstrapState.empty("test", "1.0.0")
            .withPhaseEntry(
                new PhaseStateEntry(
                    "foundation",
                    PhaseStatus.COMPLETED,
                    Instant.now(),
                    Optional.of(oldFingerprint)))
            .withManifestMetadata("test", fingerprintCalculator.manifestFingerprint(oldConfig));
    orchestrator = orchestrator(alwaysRun(), Optional.of(new InMemoryStateRepository(state)));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> orchestrator.execute(changedConfig, ignored -> {}))
        .isInstanceOf(StaleStateException.class)
        .hasMessageContaining("manifest fingerprint");

    verify(dnfExecutor, never()).install(any());
  }

  @Test
  void execute_whenDuplicatePackageSucceeds_refreshesSkipStateDuringRun() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    var skipEvaluator =
        new SkipEvaluator(
            Optional.of(stateRepository.state()),
            new InstalledProbeRegistry(List.of()),
            true,
            false);
    orchestrator = orchestrator(skipEvaluator, Optional.of(stateRepository));
    var config =
        buildConfig(
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git"), new PackageName("git")),
                    true)));

    orchestrator.execute(config, ignored -> {});

    verify(dnfExecutor, times(1)).install(any());
    assertThat(stateRepository.state().entries()).hasSize(1);
  }

  @Test
  void execute_whenAssertCommandSucceeds_completesPhase() {
    orchestrator = orchestratorWithRunner(result(0));
    var config =
        buildConfig(
            List.of(
                new AssertModule(
                    new ModuleName("secure-boot"),
                    "mokutil --sb-state",
                    "Secure Boot must be disabled",
                    "/bin/bash",
                    Optional.empty())));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.execute(config, events::add);

    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_COMPLETED);
    assertThat(events).extracting(ExecutionEvent::kind).doesNotContain(EventKind.PHASE_FAILED);
  }

  @Test
  void dryRun_whenAssertSuccessIsRecorded_stillPreviewsTheLiveCheck() {
    var assertion =
        new AssertModule(
            new ModuleName("secure-boot"),
            "mokutil --sb-state",
            "Secure Boot must be disabled",
            "/bin/bash",
            Optional.empty());
    var config = buildConfig(List.of(assertion));
    BootstrapState state =
        BootstrapState.empty("test", "1.0.0")
            .withEntry(
                new StateEntry(
                    "test",
                    "secure-boot",
                    "secure-boot",
                    ItemType.ASSERT,
                    Instant.now(),
                    Optional.empty(),
                    Optional.empty()));
    var skipEvaluator =
        new SkipEvaluator(
            Optional.of(state), new InstalledProbeRegistry(List.of()), RunStateMode.SKIP_RECORDED);
    orchestrator = orchestrator(skipEvaluator, Optional.of(new InMemoryStateRepository(state)));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(config, events::add);

    assertThat(events)
        .filteredOn(event -> event.item().equals("secure-boot"))
        .flatExtracting(event -> event.result().stream().toList())
        .singleElement()
        .isInstanceOf(StepResult.DryRun.class);
  }

  @Test
  void execute_whenAssertCommandFails_failsPhaseWithConfiguredMessage() {
    orchestrator = orchestratorWithRunner(result(1));
    var config =
        buildPhasedConfig(
            List.of(
                phase(
                    "checks",
                    false,
                    List.of(),
                    new AssertModule(
                        new ModuleName("secure-boot"),
                        "mokutil --sb-state",
                        "Disable Secure Boot before continuing.",
                        "/bin/bash",
                        Optional.empty()))));

    List<ExecutionEvent> events = new ArrayList<>();
    assertThatThrownBy(() -> orchestrator.execute(config, events::add))
        .isInstanceOf(BootstrapExecutionException.class);

    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
    var failure =
        events.stream()
            .flatMap(event -> event.result().stream())
            .filter(StepResult.Failure.class::isInstance)
            .map(StepResult.Failure.class::cast)
            .findFirst()
            .orElseThrow();
    assertThat(failure.errorMessage()).isEqualTo("Disable Secure Boot before continuing.");
  }

  @Test
  void execute_whenManualProbeSucceeds_recordsSuccess() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    orchestrator = orchestratorWithRunner(result(0), Optional.of(stateRepository));
    var config =
        buildConfig(
            List.of(
                new ManualModule(
                    new ModuleName("github-login"),
                    "Run gh auth login",
                    Optional.of("gh auth status"))));

    orchestrator.execute(config, ignored -> {});

    assertThat(stateRepository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("github-login");
  }

  @Test
  void execute_whenAptRepositoryConfigured_addsRepositoryAndRecordsSuccess() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(aptRepositoryInstaller.addTrusted(any(), eq(Optional.empty())))
        .thenReturn(new StepResult.Success("/etc/apt/sources.list.d/docker.list", Duration.ZERO));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));
    var module = aptRepositoryModule();

    orchestrator.execute(buildConfig(List.of(module)), ignored -> {});

    verify(aptRepositoryInstaller).addTrusted(module.asSourceSetup(), Optional.empty());
    assertThat(stateRepository.state().entries())
        .extracting(StateEntry::itemType)
        .containsExactly(dev.sysboot.core.ItemType.APT_REPOSITORY);
  }

  @Test
  void execute_whenRpmRepositoryConfigured_addsRepositoryAndRecordsSuccess() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(rpmRepositoryInstaller.addTrusted(any(), eq(Optional.empty())))
        .thenReturn(new StepResult.Success("/etc/yum.repos.d/docker.repo", Duration.ZERO));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));
    var module = rpmRepositoryModule();

    orchestrator.execute(buildConfig(List.of(module)), ignored -> {});

    verify(rpmRepositoryInstaller).addTrusted(module.asSourceSetup(), Optional.empty());
    assertThat(stateRepository.state().entries())
        .extracting(StateEntry::itemType)
        .containsExactly(dev.sysboot.core.ItemType.RPM_REPOSITORY);
  }

  @Test
  void execute_whenPacmanRepositoryConfigured_addsRepositoryAndRecordsSuccess() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(pacmanRepositoryInstaller.add(any()))
        .thenReturn(new StepResult.Success("chaotic-aur", Duration.ZERO));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));
    var module = pacmanRepositoryModule();

    orchestrator.execute(buildConfig(List.of(module)), ignored -> {});

    verify(pacmanRepositoryInstaller).add(module);
    assertThat(stateRepository.state().entries())
        .extracting(StateEntry::itemType)
        .containsExactly(dev.sysboot.core.ItemType.PACMAN_REPOSITORY);
  }

  @Test
  void dryRun_whenFlatpakRemoteConfigured_emitsRemoteAddCommand() {
    var module =
        new FlatpakRemoteModule(
            new ModuleName("flathub"),
            "flathub",
            URI.create("https://flathub.org/repo/flathub.flatpakrepo"),
            false,
            Optional.of(new dev.sysboot.core.Sha256Digest("a".repeat(64))));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(buildConfig(List.of(module)), events::add);

    var dryRun =
        (StepResult.DryRun)
            events.stream()
                .flatMap(event -> event.result().stream())
                .filter(StepResult.DryRun.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(dryRun.wouldExecute())
        .containsExactly(
            "sysboot-source-setup", "flatpak", "flathub", "verify-sha256=" + "a".repeat(64));
  }

  @Test
  void dryRun_whenAptRepositoryConfigured_emitsRepositoryCommand() {
    var module = aptRepositoryModule();

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(buildConfig(List.of(module)), events::add);

    var dryRun =
        (StepResult.DryRun)
            events.stream()
                .flatMap(event -> event.result().stream())
                .filter(StepResult.DryRun.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(dryRun.wouldExecute())
        .containsExactly("sysboot-source-setup", "apt", "docker", "no-remote-artifact");
  }

  @Test
  void dryRun_whenRpmRepositoryConfigured_emitsRepositoryCommand() {
    var module = rpmRepositoryModule();

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(buildConfig(List.of(module)), events::add);

    var dryRun =
        (StepResult.DryRun)
            events.stream()
                .flatMap(event -> event.result().stream())
                .filter(StepResult.DryRun.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(dryRun.wouldExecute())
        .containsExactly("sysboot-source-setup", "dnf", "docker", "no-remote-artifact");
  }

  @Test
  void dryRun_whenPacmanRepositoryConfigured_emitsRepositoryCommand() {
    var module = pacmanRepositoryModule();

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(buildConfig(List.of(module)), events::add);

    var dryRun =
        (StepResult.DryRun)
            events.stream()
                .flatMap(event -> event.result().stream())
                .filter(StepResult.DryRun.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(dryRun.wouldExecute())
        .containsExactly("sysboot-source-setup", "pacman", "chaotic-aur", "no-remote-artifact");
  }

  @Test
  void execute_whenInterruptReached_recordsCompletedWorkAndCheckpoint() {
    var repository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    var config =
        buildConfig(
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git")),
                    false),
                interrupt("checkpoint", InterruptResumeMode.CURRENT),
                new PackageModule(
                    new ModuleName("after"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("curl")),
                    false)));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> orchestrator(alwaysRun(), Optional.of(repository)).execute(config, ignored -> {}))
        .isInstanceOf(ExecutionPausedException.class);

    assertThat(repository.state().entries()).extracting(StateEntry::itemKey).containsExactly("git");
    assertThat(repository.state().planEntryEntries())
        .extracting(entry -> entry.entryName() + ":" + entry.status())
        .containsExactly("checkpoint:" + PlanEntryStatus.INTERRUPTED);
    assertThat(repository.state().nextPlanEntry()).contains("checkpoint");
    verify(dnfExecutor, times(1)).install(any());
  }

  @Test
  void dryRun_whenInterruptReached_previewsStateWriteWithoutSaving() {
    var repository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    var config =
        buildConfig(
            List.of(
                interrupt("checkpoint", InterruptResumeMode.NEXT),
                new ManualModule(new ModuleName("after"), "continue", Optional.empty())));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator(alwaysRun(), Optional.of(repository)).dryRun(config, events::add);

    assertThat(repository.state().planEntryEntries()).isEmpty();
    assertThat(repository.state().nextPlanEntry()).isEmpty();
    assertThat(events)
        .flatExtracting(event -> event.result().stream().toList())
        .filteredOn(StepResult.DryRun.class::isInstance)
        .extracting(result -> String.join(" ", ((StepResult.DryRun) result).wouldExecute()))
        .anySatisfy(
            preview ->
                assertThat(preview)
                    .contains("state-write")
                    .contains("status=completed")
                    .contains("nextPlanEntry=after"));
  }

  @Test
  void execute_whenInterruptResumeFromNext_recordsFollowingEntryAsNextPlanEntry() {
    var repository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    var config =
        buildConfig(
            List.of(
                interrupt("checkpoint", InterruptResumeMode.NEXT),
                new ManualModule(new ModuleName("after"), "continue", Optional.empty())));

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> orchestrator(alwaysRun(), Optional.of(repository)).execute(config, ignored -> {}))
        .isInstanceOf(ExecutionPausedException.class);

    assertThat(repository.state().planEntryEntries().getFirst().status())
        .isEqualTo(PlanEntryStatus.COMPLETED);
    assertThat(repository.state().nextPlanEntry()).contains("after");
  }

  @Test
  void execute_whenNextPlanEntrySaved_resumesAtThatEntry() {
    var config =
        buildConfig(
            List.of(
                interrupt("checkpoint", InterruptResumeMode.NEXT),
                new PackageModule(
                    new ModuleName("after"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("curl")),
                    false)));
    var state =
        BootstrapState.empty("test", "1.0.0")
            .withNextPlanEntry(Optional.of("after"))
            .withManifestMetadata(
                "test", new PhaseFingerprintCalculator().manifestFingerprint(config));
    var repository = new InMemoryStateRepository(state);

    var skipEvaluator =
        new SkipEvaluator(
            Optional.of(state), new InstalledProbeRegistry(List.of()), RunStateMode.SKIP_RECORDED);
    orchestrator(skipEvaluator, Optional.of(repository)).execute(config, ignored -> {});

    assertThat(repository.state().nextPlanEntry()).isEmpty();
    assertThat(repository.state().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("curl");
    verify(dnfExecutor, times(1)).install(any());
  }

  private BootstrapOrchestratorImpl orchestrator(
      SkipEvaluator skipEvaluator, Optional<StateRepository> stateRepository) {
    return new BootstrapOrchestratorImpl(
        new PackageManagerExecutorRegistry(List.of(dnfExecutor)),
        shellScriptExecutor,
        binaryInstaller,
        aptRepositoryInstaller,
        rpmRepositoryInstaller,
        pacmanRepositoryInstaller,
        new FileWriteExecutor(new DefaultShellRunner()),
        flatpakInstaller,
        flatpakRemoteInstaller,
        new DotbotExecutor(new DefaultShellRunner()),
        new DefaultShellExecutor(new DefaultShellRunner()),
        new OhMyZshExecutor(new DefaultShellRunner()),
        new ToolchainExecutor(new DefaultShellRunner()),
        new NerdFontExecutor(new DefaultShellRunner()),
        new ShellReloadExecutor(new DefaultShellRunner()),
        skipEvaluator,
        stateRepository,
        "test",
        new DefaultShellRunner(),
        dev.sysboot.core.ExecutionApproval.denyAll());
  }

  private BootstrapOrchestratorImpl orchestratorWithRunner(ProcessResult result) {
    return orchestratorWithRunner(result, Optional.empty());
  }

  private BootstrapOrchestratorImpl orchestratorWithRunner(ShellRunner runner) {
    return orchestratorWithRunner(runner, Optional.empty());
  }

  private BootstrapOrchestratorImpl orchestratorWithRunner(
      ShellRunner runner, Optional<StateRepository> stateRepository) {
    return new BootstrapOrchestratorImpl(
        new PackageManagerExecutorRegistry(List.of(dnfExecutor)),
        shellScriptExecutor,
        binaryInstaller,
        new AptRepositoryInstaller(runner),
        new RpmRepositoryInstaller(runner),
        new PacmanRepositoryInstaller(runner),
        new FileWriteExecutor(runner),
        flatpakInstaller,
        new FlatpakRemoteInstaller(runner),
        new DotbotExecutor(runner),
        new DefaultShellExecutor(runner),
        new OhMyZshExecutor(runner),
        new ToolchainExecutor(runner),
        new NerdFontExecutor(runner),
        new ShellReloadExecutor(runner),
        alwaysRun(),
        stateRepository,
        "test",
        runner,
        dev.sysboot.core.ExecutionApproval.denyAll());
  }

  private BootstrapOrchestratorImpl orchestratorWithRunner(
      ProcessResult result, Optional<StateRepository> stateRepository) {
    var runner = new FixedShellRunner(result);
    return new BootstrapOrchestratorImpl(
        new PackageManagerExecutorRegistry(List.of(dnfExecutor)),
        shellScriptExecutor,
        binaryInstaller,
        new AptRepositoryInstaller(runner),
        new RpmRepositoryInstaller(runner),
        new PacmanRepositoryInstaller(runner),
        new FileWriteExecutor(runner),
        flatpakInstaller,
        new FlatpakRemoteInstaller(runner),
        new DotbotExecutor(runner),
        new DefaultShellExecutor(runner),
        new OhMyZshExecutor(runner),
        new ToolchainExecutor(runner),
        new NerdFontExecutor(runner),
        new ShellReloadExecutor(runner),
        alwaysRun(),
        stateRepository,
        "test",
        runner,
        dev.sysboot.core.ExecutionApproval.denyAll());
  }

  private static ProcessResult result(int exitCode) {
    return new ProcessResult(exitCode, "", "", Duration.ofMillis(10));
  }

  private static ShellScriptItem localScript(String name, String path) {
    return new ShellScriptItem(
        name,
        Optional.of(new ScriptPath(Path.of(path))),
        Optional.empty(),
        List.of(),
        Optional.empty(),
        List.of(),
        false,
        List.of(0),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Duration.ofMinutes(1),
        Optional.empty());
  }

  private static List<String> dnfInstallCommand(PackageName packageName) {
    return List.of("sudo", "dnf", "install", "-y", packageName.value());
  }

  private static InterruptModule interrupt(String name, InterruptResumeMode resumeMode) {
    return new InterruptModule(
        new ModuleName(name), "Pause at " + name, List.of("Run the manual step"), resumeMode, 75);
  }

  private static PackageModule packages(String moduleName, String packageName) {
    return new PackageModule(
        new ModuleName(moduleName),
        PackageManagerKind.DNF,
        List.of(new PackageName(packageName)),
        false);
  }

  private static InstalledProbe notInstalledProbe(AtomicInteger probeCount) {
    return new InstalledProbe() {
      @Override
      public boolean supports(ItemType itemType) {
        return itemType == ItemType.PACKAGE;
      }

      @Override
      public InstallationStatus probe(String itemKey) {
        probeCount.incrementAndGet();
        return new InstallationStatus.NotInstalled(itemKey);
      }
    };
  }

  private static AptRepositoryModule aptRepositoryModule() {
    return new AptRepositoryModule(
        new ModuleName("docker"),
        "deb [signed-by=/etc/apt/keyrings/docker.gpg]"
            + " https://download.docker.com/linux/debian bookworm stable",
        Path.of("/etc/apt/sources.list.d/docker.list"),
        Optional.empty(),
        Optional.of(Path.of("/etc/apt/keyrings/docker.gpg")));
  }

  private static RpmRepositoryModule rpmRepositoryModule() {
    return new RpmRepositoryModule(
        new ModuleName("docker"),
        "docker",
        URI.create("https://download.docker.com/linux/fedora/$releasever/$basearch/stable"),
        Path.of("/etc/yum.repos.d/docker.repo"),
        Optional.empty(),
        false,
        false);
  }

  private static PacmanRepositoryModule pacmanRepositoryModule() {
    return new PacmanRepositoryModule(
        new ModuleName("chaotic-aur"),
        "chaotic-aur",
        URI.create("https://cdn-mirror.chaotic.cx/$repo/$arch"),
        Path.of("/etc/pacman.conf"),
        Optional.of("Required TrustedOnly"),
        Optional.empty(),
        true);
  }

  private static BootstrapConfig buildConfig(List<dev.sysboot.core.BootstrapModule> modules) {
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("41"));
    modules.forEach(builder::addModule);
    return builder.build();
  }

  private static BootstrapConfig buildConfig(
      List<dev.sysboot.core.SourceSetup> sourceSetups,
      List<dev.sysboot.core.BootstrapModule> modules) {
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("41"))
            .sourceSetups(sourceSetups);
    modules.forEach(builder::addModule);
    return builder.build();
  }

  private static BootstrapConfig configWithSourceAndPackage(BootstrapPolicy policy) {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("test"))
        .target(new OsTarget.FedoraTarget("41"))
        .policy(policy)
        .sourceSetups(List.of(executableDnfSourceSetup()))
        .addModule(
            new PackageModule(
                new ModuleName("tools"),
                PackageManagerKind.DNF,
                List.of(new PackageName("git")),
                true))
        .build();
  }

  private static RpmRepositorySourceSetup dnfSourceSetup() {
    return new RpmRepositorySourceSetup(
        new ModuleName("docker"),
        "docker",
        URI.create("https://download.docker.com/linux/fedora/$releasever/$basearch/stable"),
        Path.of("/etc/yum.repos.d/docker.repo"),
        Optional.of(URI.create("https://download.docker.com/linux/fedora/gpg")),
        true,
        true,
        Optional.of(new dev.sysboot.core.Sha256Digest("a".repeat(64))));
  }

  private static RpmRepositorySourceSetup executableDnfSourceSetup() {
    return new RpmRepositorySourceSetup(
        new ModuleName("docker"),
        "docker",
        URI.create("https://download.docker.com/linux/fedora/$releasever/$basearch/stable"),
        Path.of("/etc/yum.repos.d/docker.repo"),
        Optional.empty(),
        false,
        false,
        Optional.empty());
  }

  private static BootstrapConfig buildPhasedConfig(List<Phase> phases) {
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("41"));
    phases.forEach(builder::addPhase);
    return builder.build();
  }

  private static Phase phase(
      String name,
      boolean continueOnModuleError,
      List<PhaseName> dependsOn,
      dev.sysboot.core.BootstrapModule module) {
    return phase(name, continueOnModuleError, dependsOn, List.of(module));
  }

  private static Phase phase(
      String name,
      boolean continueOnModuleError,
      List<PhaseName> dependsOn,
      dev.sysboot.core.BootstrapModule... modules) {
    return phase(name, continueOnModuleError, dependsOn, List.of(modules));
  }

  private static Phase phase(
      String name,
      boolean continueOnModuleError,
      List<PhaseName> dependsOn,
      List<dev.sysboot.core.BootstrapModule> modules) {
    return new Phase(
        new PhaseName(name),
        "",
        modules,
        dependsOn,
        new RestartPolicy.None(),
        continueOnModuleError);
  }

  private static URI binaryUri() {
    return URI.create("https://example.test/rg");
  }

  private static String sha256(byte[] body) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
    return HexFormat.of().formatHex(digest);
  }

  private record FakeDownloadClient(Map<URI, byte[]> downloads) implements BinaryDownloadClient {
    @Override
    public void downloadToFile(URI url, Path destination) throws IOException {
      Files.write(destination, downloads.get(url));
    }

    @Override
    public String downloadText(URI url) {
      throw new UnsupportedOperationException("not used");
    }
  }

  private static final class InMemoryStateRepository implements StateRepository {

    private BootstrapState state;

    private InMemoryStateRepository(BootstrapState state) {
      this.state = state;
    }

    private BootstrapState state() {
      return state;
    }

    @Override
    public Optional<BootstrapState> load(String profileName) {
      return state.profileName().equals(profileName) ? Optional.of(state) : Optional.empty();
    }

    @Override
    public void save(BootstrapState state) {
      this.state = state;
    }

    @Override
    public BootstrapState recordSuccess(String profileName, StateEntry entry) {
      state = load(profileName).orElse(BootstrapState.empty(profileName, "1.0.0")).withEntry(entry);
      return state;
    }

    @Override
    public void reset(String profileName) {
      if (state.profileName().equals(profileName)) {
        state = BootstrapState.empty(profileName, "1.0.0");
      }
    }

    @Override
    public Optional<BootstrapState> forgetItem(String profileName, String itemKey) {
      Optional<BootstrapState> current = load(profileName);
      current.map(existing -> existing.withoutItem(itemKey)).ifPresent(this::save);
      return load(profileName);
    }

    @Override
    public Optional<BootstrapState> forgetPhase(String profileName, String phaseName) {
      Optional<BootstrapState> current = load(profileName);
      current.map(existing -> existing.withoutPhase(phaseName)).ifPresent(this::save);
      return load(profileName);
    }
  }

  private record FixedShellRunner(ProcessResult result) implements ShellRunner {

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      return result;
    }
  }

  private static final class FailingShellRunner implements ShellRunner {

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      throw new AssertionError("dry-run must not execute command: " + command);
    }
  }
}
