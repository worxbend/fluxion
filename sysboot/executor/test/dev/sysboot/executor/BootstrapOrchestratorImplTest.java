package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.core.StepResult;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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

  @Mock private FlatpakInstaller flatpakInstaller;

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

    var registry = new PackageManagerExecutorRegistry(List.of(dnfExecutor));
    orchestrator =
        new BootstrapOrchestratorImpl(
            registry,
            shellScriptExecutor,
            binaryInstaller,
            flatpakInstaller,
            alwaysRun(),
            Optional.empty(),
            "test");
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
  void execute_whenPackageFailsAndContinueOnErrorFalse_stopsAfterFailure() {
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
                    false)));

    List<ExecutionEvent> events = new ArrayList<>();
    orchestrator.execute(config, events::add);

    verify(dnfExecutor, times(1)).install(any());
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
                        true)),
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
                        true)),
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

  private BootstrapOrchestratorImpl orchestrator(
      SkipEvaluator skipEvaluator, Optional<StateRepository> stateRepository) {
    return new BootstrapOrchestratorImpl(
        new PackageManagerExecutorRegistry(List.of(dnfExecutor)),
        shellScriptExecutor,
        binaryInstaller,
        flatpakInstaller,
        skipEvaluator,
        stateRepository,
        "test");
  }

  private static List<String> dnfInstallCommand(PackageName packageName) {
    return List.of("sudo", "dnf", "install", "-y", packageName.value());
  }

  private static BootstrapConfig buildConfig(List<dev.sysboot.core.BootstrapModule> modules) {
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("41"));
    modules.forEach(builder::addModule);
    return builder.build();
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
    return new Phase(
        new PhaseName(name),
        "",
        List.of(module),
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
}
