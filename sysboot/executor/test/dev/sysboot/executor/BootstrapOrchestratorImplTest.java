package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleName;
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
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.core.StepResult;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    orchestrator.execute(config, events::add);

    verify(dnfExecutor, times(2)).install(any());
    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
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

    orchestrator.execute(config, ignored -> {});

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
  void execute_whenFlatpakMiddleAppFails_attemptsLaterAppBeforeAggregateFailure() {
    when(flatpakInstaller.install(any(), org.mockito.ArgumentMatchers.eq("org.first")))
        .thenReturn(new StepResult.Success("org.first", Duration.ZERO));
    when(flatpakInstaller.install(any(), org.mockito.ArgumentMatchers.eq("org.broken")))
        .thenReturn(new StepResult.Failure("org.broken", "failed", 1, Duration.ZERO));
    when(flatpakInstaller.install(any(), org.mockito.ArgumentMatchers.eq("org.last")))
        .thenReturn(new StepResult.Success("org.last", Duration.ZERO));
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
                        List.of("org.first", "org.broken", "org.last"),
                        false))));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.execute(config, events::add);

    verify(flatpakInstaller).install(any(), org.mockito.ArgumentMatchers.eq("org.first"));
    verify(flatpakInstaller).install(any(), org.mockito.ArgumentMatchers.eq("org.broken"));
    verify(flatpakInstaller).install(any(), org.mockito.ArgumentMatchers.eq("org.last"));
    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
  }

  @Test
  void execute_whenPhaseAllowsModuleErrors_completesPhaseAndRunsDependentPhase() {
    when(dnfExecutor.install(any()))
        .thenReturn(new StepResult.Failure("git", "not found", 1, Duration.ofMillis(100)))
        .thenReturn(new StepResult.Success("curl", Duration.ofMillis(100)));

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
    orchestrator.execute(config, events::add);

    assertThat(events).extracting(ExecutionEvent::kind).doesNotContain(EventKind.PHASE_FAILED);
    assertThat(events)
        .filteredOn(e -> e.kind() == EventKind.PHASE_COMPLETED)
        .extracting(e -> e.phaseContext().orElseThrow())
        .contains("foundation", "dependent");
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
                        false))));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.execute(config, events::add);

    assertThat(events).extracting(ExecutionEvent::kind).contains(EventKind.PHASE_FAILED);
    assertThat(events)
        .filteredOn(e -> e.kind() == EventKind.PHASE_BLOCKED)
        .extracting(e -> e.phaseContext().orElseThrow())
        .containsExactly("dependent");
    assertThat(stateRepository.state().findPhaseEntry("foundation").orElseThrow().reason())
        .contains("Phase stopped after a module failure");
    assertThat(stateRepository.state().findPhaseEntry("dependent").orElseThrow().reason())
        .contains("Blocked by failed phase: foundation");
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
    when(rpmRepositoryInstaller.addCommand(any()))
        .thenReturn(List.of("/bin/bash", "-lc", "sudo dnf makecache --refresh"));
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
  void execute_whenRequiredSourceSetupFails_doesNotInstallPackages() {
    when(rpmRepositoryInstaller.add(any()))
        .thenReturn(
            new StepResult.Failure(
                "/etc/yum.repos.d/docker.repo", "failed", 1, Duration.ZERO));
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));

    orchestrator.execute(configWithSourceAndPackage(BootstrapPolicy.empty()), ignored -> {});

    verify(dnfExecutor, never()).install(any());
    assertThat(stateRepository.state().entries()).isEmpty();
  }

  @Test
  void execute_whenSourceSetupFailsAndPolicyAllowsContinuation_installsPackages() {
    when(rpmRepositoryInstaller.add(any()))
        .thenReturn(
            new StepResult.Failure(
                "/etc/yum.repos.d/docker.repo", "failed", 1, Duration.ZERO));
    var policy = new BootstrapPolicy(Optional.empty(), Optional.of(true), Optional.empty());

    orchestrator.execute(configWithSourceAndPackage(policy), ignored -> {});

    verify(dnfExecutor).install(new PackageName("git"));
  }

  @Test
  void execute_whenSourceSetupSucceeds_recordsRepositoryStateBeforePackageState() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(rpmRepositoryInstaller.add(any()))
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
    String fingerprint = new PhaseFingerprintCalculator().fingerprint(phase);
    BootstrapState state =
        BootstrapState.empty("test", "1.0.0")
            .withPhaseEntry(
                new PhaseStateEntry(
                    "foundation", PhaseStatus.COMPLETED, Instant.now(), Optional.of(fingerprint)));
    orchestrator = orchestrator(alwaysRun(), Optional.of(new InMemoryStateRepository(state)));

    orchestrator.execute(buildPhasedConfig(List.of(phase)), ignored -> {});

    verify(dnfExecutor, never()).install(any());
  }

  @Test
  void execute_whenCompletedPhaseFingerprintDiffers_runsPhaseAgain() {
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
    String oldFingerprint = new PhaseFingerprintCalculator().fingerprint(oldPhase);
    BootstrapState state =
        BootstrapState.empty("test", "1.0.0")
            .withPhaseEntry(
                new PhaseStateEntry(
                    "foundation",
                    PhaseStatus.COMPLETED,
                    Instant.now(),
                    Optional.of(oldFingerprint)));
    orchestrator = orchestrator(alwaysRun(), Optional.of(new InMemoryStateRepository(state)));

    orchestrator.execute(buildPhasedConfig(List.of(changedPhase)), ignored -> {});

    verify(dnfExecutor, times(2)).install(any());
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
    orchestrator.execute(config, events::add);

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
  void execute_whenFlatpakRemoteConfigured_addsRemoteAndRecordsSuccess() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(flatpakRemoteInstaller.add(any()))
        .thenReturn(new StepResult.Success("flathub", Duration.ofMillis(25)));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));
    var module =
        new FlatpakRemoteModule(
            new ModuleName("flathub"),
            "flathub",
            URI.create("https://flathub.org/repo/flathub.flatpakrepo"),
            true);

    orchestrator.execute(buildConfig(List.of(module)), ignored -> {});

    verify(flatpakRemoteInstaller).add(module);
    assertThat(stateRepository.state().entries())
        .extracting(StateEntry::itemType)
        .containsExactly(dev.sysboot.core.ItemType.FLATPAK_REMOTE);
  }

  @Test
  void execute_whenAptRepositoryConfigured_addsRepositoryAndRecordsSuccess() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(aptRepositoryInstaller.add(any()))
        .thenReturn(new StepResult.Success("/etc/apt/sources.list.d/docker.list", Duration.ZERO));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));
    var module = aptRepositoryModule();

    orchestrator.execute(buildConfig(List.of(module)), ignored -> {});

    verify(aptRepositoryInstaller).add(module);
    assertThat(stateRepository.state().entries())
        .extracting(StateEntry::itemType)
        .containsExactly(dev.sysboot.core.ItemType.APT_REPOSITORY);
  }

  @Test
  void execute_whenRpmRepositoryConfigured_addsRepositoryAndRecordsSuccess() {
    var stateRepository = new InMemoryStateRepository(BootstrapState.empty("test", "1.0.0"));
    when(rpmRepositoryInstaller.add(any()))
        .thenReturn(new StepResult.Success("/etc/yum.repos.d/docker.repo", Duration.ZERO));
    orchestrator = orchestrator(alwaysRun(), Optional.of(stateRepository));
    var module = rpmRepositoryModule();

    orchestrator.execute(buildConfig(List.of(module)), ignored -> {});

    verify(rpmRepositoryInstaller).add(module);
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
            false);
    when(flatpakRemoteInstaller.addCommand(module))
        .thenReturn(
            List.of(
                "flatpak",
                "--user",
                "remote-add",
                "--if-not-exists",
                "flathub",
                "https://flathub.org/repo/flathub.flatpakrepo"));

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
            "flatpak",
            "--user",
            "remote-add",
            "--if-not-exists",
            "flathub",
            "https://flathub.org/repo/flathub.flatpakrepo");
  }

  @Test
  void dryRun_whenAptRepositoryConfigured_emitsRepositoryCommand() {
    var module = aptRepositoryModule();
    when(aptRepositoryInstaller.addCommand(module))
        .thenReturn(List.of("/bin/bash", "-lc", "sudo apt-get update"));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(buildConfig(List.of(module)), events::add);

    var dryRun =
        (StepResult.DryRun)
            events.stream()
                .flatMap(event -> event.result().stream())
                .filter(StepResult.DryRun.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(dryRun.wouldExecute()).containsExactly("/bin/bash", "-lc", "sudo apt-get update");
  }

  @Test
  void dryRun_whenRpmRepositoryConfigured_emitsRepositoryCommand() {
    var module = rpmRepositoryModule();
    when(rpmRepositoryInstaller.addCommand(module))
        .thenReturn(List.of("/bin/bash", "-lc", "sudo dnf makecache --refresh"));

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
        .containsExactly("/bin/bash", "-lc", "sudo dnf makecache --refresh");
  }

  @Test
  void dryRun_whenPacmanRepositoryConfigured_emitsRepositoryCommand() {
    var module = pacmanRepositoryModule();
    when(pacmanRepositoryInstaller.addCommand(module))
        .thenReturn(List.of("/bin/bash", "-lc", "sudo pacman -Sy"));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.dryRun(buildConfig(List.of(module)), events::add);

    var dryRun =
        (StepResult.DryRun)
            events.stream()
                .flatMap(event -> event.result().stream())
                .filter(StepResult.DryRun.class::isInstance)
                .findFirst()
                .orElseThrow();
    assertThat(dryRun.wouldExecute()).containsExactly("/bin/bash", "-lc", "sudo pacman -Sy");
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
        new DefaultShellRunner());
  }

  private BootstrapOrchestratorImpl orchestratorWithRunner(ProcessResult result) {
    return orchestratorWithRunner(result, Optional.empty());
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
        new DefaultShellRunner());
  }

  private static ProcessResult result(int exitCode) {
    return new ProcessResult(exitCode, "", "", Duration.ofMillis(10));
  }

  private static List<String> dnfInstallCommand(PackageName packageName) {
    return List.of("sudo", "dnf", "install", "-y", packageName.value());
  }

  private static AptRepositoryModule aptRepositoryModule() {
    return new AptRepositoryModule(
        new ModuleName("docker"),
        "deb [signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian"
            + " bookworm stable",
        Path.of("/etc/apt/sources.list.d/docker.list"),
        Optional.of(URI.create("https://download.docker.com/linux/debian/gpg")),
        Optional.of(Path.of("/etc/apt/keyrings/docker.gpg")));
  }

  private static RpmRepositoryModule rpmRepositoryModule() {
    return new RpmRepositoryModule(
        new ModuleName("docker"),
        "docker",
        URI.create("https://download.docker.com/linux/fedora/$releasever/$basearch/stable"),
        Path.of("/etc/yum.repos.d/docker.repo"),
        Optional.of(URI.create("https://download.docker.com/linux/fedora/gpg")),
        true,
        true);
  }

  private static PacmanRepositoryModule pacmanRepositoryModule() {
    return new PacmanRepositoryModule(
        new ModuleName("chaotic-aur"),
        "chaotic-aur",
        URI.create("https://cdn-mirror.chaotic.cx/$repo/$arch"),
        Path.of("/etc/pacman.conf"),
        Optional.of("Required DatabaseOptional"),
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
        .sourceSetups(List.of(dnfSourceSetup()))
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
        true);
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
}
