package dev.sysboot.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.config.YamlConfigLoader;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.ConfigLoader;
import dev.sysboot.core.ExecutionApproval;
import dev.sysboot.core.HostFactsProvider;
import dev.sysboot.core.PrivilegeGate;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import dev.sysboot.executor.AptPackageInstaller;
import dev.sysboot.executor.AptPackageProbe;
import dev.sysboot.executor.AptRepositoryInstaller;
import dev.sysboot.executor.AptRepositoryProbe;
import dev.sysboot.executor.BootstrapOrchestratorImpl;
import dev.sysboot.executor.CargoPackageInstaller;
import dev.sysboot.executor.CompiledBinaryInstaller;
import dev.sysboot.executor.CompiledBinaryProbe;
import dev.sysboot.executor.DefaultShellExecutor;
import dev.sysboot.executor.DefaultShellProbe;
import dev.sysboot.executor.DefaultShellRunner;
import dev.sysboot.executor.DnfPackageInstaller;
import dev.sysboot.executor.DnfPackageProbe;
import dev.sysboot.executor.DotbotExecutor;
import dev.sysboot.executor.DotbotProbe;
import dev.sysboot.executor.ExecutionPlanBuilder;
import dev.sysboot.executor.FileWriteExecutor;
import dev.sysboot.executor.FlatpakInstaller;
import dev.sysboot.executor.FlatpakProbe;
import dev.sysboot.executor.FlatpakRemoteInstaller;
import dev.sysboot.executor.FlatpakRemoteProbe;
import dev.sysboot.executor.InstalledProbeRegistry;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.LinuxHostFactsProvider;
import dev.sysboot.executor.NerdFontExecutor;
import dev.sysboot.executor.NerdFontProbe;
import dev.sysboot.executor.OhMyZshExecutor;
import dev.sysboot.executor.OncePrivilegeGate;
import dev.sysboot.executor.PackageManagerExecutorRegistry;
import dev.sysboot.executor.PacmanPackageInstaller;
import dev.sysboot.executor.PacmanPackageProbe;
import dev.sysboot.executor.PacmanRepositoryInstaller;
import dev.sysboot.executor.PacmanRepositoryProbe;
import dev.sysboot.executor.ParallelProbeRunner;
import dev.sysboot.executor.ParuPackageInstaller;
import dev.sysboot.executor.PolicyPrivilegeOrchestrator;
import dev.sysboot.executor.PtyShellRunner;
import dev.sysboot.executor.RpmRepositoryInstaller;
import dev.sysboot.executor.RpmRepositoryProbe;
import dev.sysboot.executor.RunStateMode;
import dev.sysboot.executor.ShellReloadExecutor;
import dev.sysboot.executor.ShellScriptExecutor;
import dev.sysboot.executor.ShellScriptProbe;
import dev.sysboot.executor.SkipEvaluator;
import dev.sysboot.executor.StateReadException;
import dev.sysboot.executor.SudoPrivilegePreflight;
import dev.sysboot.executor.SudoSession;
import dev.sysboot.executor.ToolchainExecutor;
import dev.sysboot.executor.YayPackageInstaller;
import dev.sysboot.executor.ZypperPackageInstaller;
import dev.sysboot.executor.ZypperPackageProbe;
import dev.sysboot.executor.ZypperRepositoryProbe;
import dev.sysboot.tui.SysbootTuiApp;
import dev.sysboot.tui.TuiExecutionEventListener;
import dev.sysboot.tui.TuiSudoPasswordProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ApplicationContext implements AutoCloseable {

  private final BootstrapOrchestrator orchestrator;
  private final ConfigLoader configLoader;
  private final Optional<SysbootTuiApp> tuiApp;
  private final ParallelProbeRunner parallelProbeRunner;
  private final ExecutionPlanBuilder executionPlanBuilder;
  private final HostFactsProvider hostFactsProvider;
  private final Optional<SudoSession> sudoSession;
  private final PrivilegeGate privilegeGate;

  private ApplicationContext(
      BootstrapOrchestrator orchestrator,
      ConfigLoader configLoader,
      Optional<SysbootTuiApp> tuiApp,
      ParallelProbeRunner parallelProbeRunner,
      ExecutionPlanBuilder executionPlanBuilder,
      HostFactsProvider hostFactsProvider,
      Optional<SudoSession> sudoSession,
      PrivilegeGate privilegeGate) {
    this.orchestrator = orchestrator;
    this.configLoader = configLoader;
    this.tuiApp = tuiApp;
    this.parallelProbeRunner = parallelProbeRunner;
    this.executionPlanBuilder = executionPlanBuilder;
    this.hostFactsProvider = hostFactsProvider;
    this.sudoSession = sudoSession;
    this.privilegeGate = privilegeGate;
  }

  /**
   * Releases resources held for the run.
   *
   * <p>Currently that means zeroing the cached sudo password and stopping its keepalive thread.
   * Without an explicit call the password stayed on the heap for the life of the process and the
   * keepalive kept sudo's timestamp valid long after Fluxion had finished.
   */
  @Override
  public void close() {
    sudoSession.ifPresent(SudoSession::close);
  }

  public BootstrapOrchestrator orchestrator() {
    return orchestrator;
  }

  public ConfigLoader configLoader() {
    return configLoader;
  }

  public Optional<SysbootTuiApp> tuiApp() {
    return tuiApp;
  }

  public ParallelProbeRunner parallelProbeRunner() {
    return parallelProbeRunner;
  }

  public ExecutionPlanBuilder executionPlanBuilder() {
    return executionPlanBuilder;
  }

  public HostFactsProvider hostFactsProvider() {
    return hostFactsProvider;
  }

  public void preflight(BootstrapConfig config) {
    if (tuiApp.isPresent()) {
      tuiApp.orElseThrow().runPrivilegePreflight(() -> privilegeGate.verify(config));
      return;
    }
    privilegeGate.verify(config);
  }

  public static ApplicationContext create(
      boolean noTui, String profile, boolean skipAlreadyInstalled, boolean reProbe) {
    return create(noTui, profile, skipAlreadyInstalled, reProbe, ExecutionApproval.denyAll());
  }

  public static ApplicationContext create(
      boolean noTui,
      String profile,
      boolean skipAlreadyInstalled,
      boolean reProbe,
      ExecutionApproval approval) {
    return create(noTui, profile, skipAlreadyInstalled, reProbe, approval, false);
  }

  public static ApplicationContext create(
      boolean noTui,
      String profile,
      boolean skipAlreadyInstalled,
      boolean reProbe,
      ExecutionApproval approval,
      boolean readOnlyState) {
    if (noTui) {
      return forCli(profile, skipAlreadyInstalled, reProbe, approval, readOnlyState);
    }
    return forTui(profile, skipAlreadyInstalled, reProbe, approval, readOnlyState);
  }

  public static ApplicationContext create(boolean noTui) {
    return create(noTui, "default", false, false);
  }

  public static ApplicationContext forTui(
      String profile, boolean skipAlreadyInstalled, boolean reProbe) {
    return forTui(profile, skipAlreadyInstalled, reProbe, ExecutionApproval.denyAll());
  }

  public static ApplicationContext forTui(
      String profile, boolean skipAlreadyInstalled, boolean reProbe, ExecutionApproval approval) {
    return forTui(profile, skipAlreadyInstalled, reProbe, approval, false);
  }

  private static ApplicationContext forTui(
      String profile,
      boolean skipAlreadyInstalled,
      boolean reProbe,
      ExecutionApproval approval,
      boolean readOnlyState) {
    // The TUI drives the prompt; the session sits between the prompt and the shell runners so a
    // run asks for the sudo password once instead of once per privileged command.
    var sudoPrompt = new TuiSudoPasswordProvider();
    var sudoProvider = new SudoSession(sudoPrompt);
    var eventListener = new TuiExecutionEventListener();
    var ptyRunner = new PtyShellRunner(sudoProvider);
    var baseRunner = new DefaultShellRunner();
    var mapper = new ObjectMapper();
    var stateRepo = new JsonStateRepository(mapper, readOnlyState);
    var hostFactsProvider = new LinuxHostFactsProvider();

    var probeRegistry = buildProbeRegistry(baseRunner);
    RunStateMode runStateMode = RunStateMode.fromOptions(skipAlreadyInstalled, reProbe);
    var skipEvaluator =
        buildSkipEvaluator(stateRepo, probeRegistry, profile, runStateMode, readOnlyState);
    var registry = buildExecutorRegistry(ptyRunner, sudoProvider);
    PrivilegeGate privilegeGate =
        new OncePrivilegeGate(SudoPrivilegePreflight.interactive(sudoProvider));
    var orchestrator =
        buildOrchestrator(
            registry,
            ptyRunner,
            skipEvaluator,
            Optional.of(stateRepo),
            profile,
            approval,
            privilegeGate);
    var tuiApp = new SysbootTuiApp(orchestrator, eventListener, sudoPrompt, List.of());

    return new ApplicationContext(
        orchestrator,
        new YamlConfigLoader(hostFactsProvider),
        Optional.of(tuiApp),
        new ParallelProbeRunner(probeRegistry),
        new ExecutionPlanBuilder(registry),
        hostFactsProvider,
        Optional.of(sudoProvider),
        privilegeGate);
  }

  public static ApplicationContext forCli(
      String profile, boolean skipAlreadyInstalled, boolean reProbe) {
    return forCli(profile, skipAlreadyInstalled, reProbe, ExecutionApproval.denyAll());
  }

  public static ApplicationContext forCli(
      String profile, boolean skipAlreadyInstalled, boolean reProbe, ExecutionApproval approval) {
    return forCli(profile, skipAlreadyInstalled, reProbe, approval, false);
  }

  private static ApplicationContext forCli(
      String profile,
      boolean skipAlreadyInstalled,
      boolean reProbe,
      ExecutionApproval approval,
      boolean readOnlyState) {
    SudoPasswordProvider noopSudo = prompt -> Optional.empty();
    var shellRunner = new DefaultShellRunner();
    var mapper = new ObjectMapper();
    var stateRepo = new JsonStateRepository(mapper, readOnlyState);
    var hostFactsProvider = new LinuxHostFactsProvider();

    var probeRegistry = buildProbeRegistry(shellRunner);
    RunStateMode runStateMode = RunStateMode.fromOptions(skipAlreadyInstalled, reProbe);
    var skipEvaluator =
        buildSkipEvaluator(stateRepo, probeRegistry, profile, runStateMode, readOnlyState);
    var registry = buildExecutorRegistry(shellRunner, noopSudo);
    PrivilegeGate privilegeGate =
        new OncePrivilegeGate(SudoPrivilegePreflight.nonInteractive(shellRunner));
    var orchestrator =
        buildOrchestrator(
            registry,
            shellRunner,
            skipEvaluator,
            Optional.of(stateRepo),
            profile,
            approval,
            privilegeGate);

    return new ApplicationContext(
        orchestrator,
        new YamlConfigLoader(hostFactsProvider),
        Optional.empty(),
        new ParallelProbeRunner(probeRegistry),
        new ExecutionPlanBuilder(registry),
        hostFactsProvider,
        Optional.empty(),
        privilegeGate);
  }

  private static BootstrapOrchestrator buildOrchestrator(
      PackageManagerExecutorRegistry registry,
      ShellRunner effectRunner,
      SkipEvaluator skipEvaluator,
      Optional<JsonStateRepository> stateRepo,
      String profile,
      ExecutionApproval approval,
      PrivilegeGate privilegeGate) {
    var delegate =
        new BootstrapOrchestratorImpl(
            registry,
            new ShellScriptExecutor(effectRunner, approval),
            new CompiledBinaryInstaller(effectRunner),
            new AptRepositoryInstaller(effectRunner),
            new RpmRepositoryInstaller(effectRunner),
            new PacmanRepositoryInstaller(effectRunner),
            new FileWriteExecutor(effectRunner),
            new FlatpakInstaller(effectRunner),
            new FlatpakRemoteInstaller(effectRunner),
            new DotbotExecutor(effectRunner),
            new DefaultShellExecutor(effectRunner),
            new OhMyZshExecutor(effectRunner),
            new ToolchainExecutor(effectRunner),
            new NerdFontExecutor(effectRunner),
            new ShellReloadExecutor(effectRunner),
            skipEvaluator,
            stateRepo.map(r -> (dev.sysboot.core.StateRepository) r),
            profile,
            effectRunner,
            approval);
    return new PolicyPrivilegeOrchestrator(delegate, privilegeGate);
  }

  private static InstalledProbeRegistry buildProbeRegistry(ShellRunner runner) {
    return new InstalledProbeRegistry(
        List.of(
            new DnfPackageProbe(runner),
            new PacmanPackageProbe(runner),
            new AptPackageProbe(runner),
            new AptRepositoryProbe(runner),
            new RpmRepositoryProbe(runner),
            new PacmanRepositoryProbe(runner),
            new ZypperRepositoryProbe(runner),
            new ZypperPackageProbe(runner),
            new FlatpakProbe(runner),
            new FlatpakRemoteProbe(runner),
            new CompiledBinaryProbe(Optional.empty(), Optional.empty()),
            new ShellScriptProbe(runner, Map.of()),
            new DotbotProbe(runner, Map.of()),
            new DefaultShellProbe(runner),
            new NerdFontProbe(runner)));
  }

  private static SkipEvaluator buildSkipEvaluator(
      JsonStateRepository stateRepo,
      InstalledProbeRegistry probeRegistry,
      String profile,
      RunStateMode runStateMode,
      boolean readOnlyState) {
    Optional<dev.sysboot.core.BootstrapState> state =
        runStateMode == RunStateMode.SKIP_RECORDED
            ? loadState(stateRepo, profile, readOnlyState)
            : Optional.empty();
    return new SkipEvaluator(state, probeRegistry, runStateMode);
  }

  private static Optional<dev.sysboot.core.BootstrapState> loadState(
      JsonStateRepository stateRepo, String profile, boolean readOnlyState) {
    if (!readOnlyState) {
      return stateRepo.load(profile);
    }
    try {
      return stateRepo.loadReadOnly(profile);
    } catch (StateReadException ignored) {
      // A preview can fall back to live probes, but must not repair state merely to read it.
      return Optional.empty();
    }
  }

  private static PackageManagerExecutorRegistry buildExecutorRegistry(
      ShellRunner runner, SudoPasswordProvider sudo) {
    return new PackageManagerExecutorRegistry(
        List.of(
            new DnfPackageInstaller(runner, sudo),
            new PacmanPackageInstaller(runner, sudo),
            new ParuPackageInstaller(runner, sudo),
            new YayPackageInstaller(runner, sudo),
            new AptPackageInstaller(runner, sudo),
            new ZypperPackageInstaller(runner, sudo),
            new CargoPackageInstaller(runner, sudo)));
  }
}
