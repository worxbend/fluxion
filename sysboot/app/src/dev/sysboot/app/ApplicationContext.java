package dev.sysboot.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.config.YamlConfigLoader;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.ConfigLoader;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.SudoPasswordProvider;
import dev.sysboot.executor.AptPackageInstaller;
import dev.sysboot.executor.AptPackageProbe;
import dev.sysboot.executor.BootstrapOrchestratorImpl;
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
import dev.sysboot.executor.FlatpakInstaller;
import dev.sysboot.executor.FlatpakProbe;
import dev.sysboot.executor.FlatpakRemoteInstaller;
import dev.sysboot.executor.FlatpakRemoteProbe;
import dev.sysboot.executor.InstalledProbeRegistry;
import dev.sysboot.executor.JsonStateRepository;
import dev.sysboot.executor.NerdFontExecutor;
import dev.sysboot.executor.NerdFontProbe;
import dev.sysboot.executor.OhMyZshExecutor;
import dev.sysboot.executor.PackageManagerExecutorRegistry;
import dev.sysboot.executor.PacmanPackageInstaller;
import dev.sysboot.executor.PacmanPackageProbe;
import dev.sysboot.executor.ParallelProbeRunner;
import dev.sysboot.executor.ParuPackageInstaller;
import dev.sysboot.executor.PtyShellRunner;
import dev.sysboot.executor.ShellReloadExecutor;
import dev.sysboot.executor.ShellScriptExecutor;
import dev.sysboot.executor.ShellScriptProbe;
import dev.sysboot.executor.SkipEvaluator;
import dev.sysboot.executor.ToolchainExecutor;
import dev.sysboot.executor.YayPackageInstaller;
import dev.sysboot.executor.ZypperPackageInstaller;
import dev.sysboot.executor.ZypperPackageProbe;
import dev.sysboot.tui.SysbootTuiApp;
import dev.sysboot.tui.TuiExecutionEventListener;
import dev.sysboot.tui.TuiSudoPasswordProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ApplicationContext {

  private final BootstrapOrchestrator orchestrator;
  private final ConfigLoader configLoader;
  private final Optional<SysbootTuiApp> tuiApp;
  private final ParallelProbeRunner parallelProbeRunner;
  private final ExecutionPlanBuilder executionPlanBuilder;

  private ApplicationContext(
      BootstrapOrchestrator orchestrator,
      ConfigLoader configLoader,
      Optional<SysbootTuiApp> tuiApp,
      ParallelProbeRunner parallelProbeRunner,
      ExecutionPlanBuilder executionPlanBuilder) {
    this.orchestrator = orchestrator;
    this.configLoader = configLoader;
    this.tuiApp = tuiApp;
    this.parallelProbeRunner = parallelProbeRunner;
    this.executionPlanBuilder = executionPlanBuilder;
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

  public static ApplicationContext create(
      boolean noTui, String profile, boolean skipAlreadyInstalled, boolean reProbe) {
    if (noTui) {
      return forCli(profile, skipAlreadyInstalled, reProbe);
    }
    return forTui(profile, skipAlreadyInstalled, reProbe);
  }

  public static ApplicationContext create(boolean noTui) {
    return create(noTui, "default", false, false);
  }

  public static ApplicationContext forTui(
      String profile, boolean skipAlreadyInstalled, boolean reProbe) {
    var sudoProvider = new TuiSudoPasswordProvider();
    var eventListener = new TuiExecutionEventListener();
    var ptyRunner = new PtyShellRunner(sudoProvider);
    var baseRunner = new DefaultShellRunner();
    var mapper = new ObjectMapper();
    var stateRepo = new JsonStateRepository(mapper);

    var probeRegistry = buildProbeRegistry(baseRunner);
    var skipEvaluator =
        buildSkipEvaluator(stateRepo, probeRegistry, profile, skipAlreadyInstalled, reProbe);
    var registry = buildExecutorRegistry(ptyRunner, sudoProvider);
    var orchestrator =
        buildOrchestrator(
            registry, ptyRunner, baseRunner, skipEvaluator, Optional.of(stateRepo), profile);
    var tuiApp = new SysbootTuiApp(orchestrator, eventListener, sudoProvider, List.of());

    return new ApplicationContext(
        orchestrator,
        new YamlConfigLoader(),
        Optional.of(tuiApp),
        new ParallelProbeRunner(probeRegistry),
        new ExecutionPlanBuilder(registry));
  }

  public static ApplicationContext forCli(
      String profile, boolean skipAlreadyInstalled, boolean reProbe) {
    SudoPasswordProvider noopSudo = prompt -> Optional.empty();
    var shellRunner = new DefaultShellRunner();
    var mapper = new ObjectMapper();
    var stateRepo = new JsonStateRepository(mapper);

    var probeRegistry = buildProbeRegistry(shellRunner);
    var skipEvaluator =
        buildSkipEvaluator(stateRepo, probeRegistry, profile, skipAlreadyInstalled, reProbe);
    var registry = buildExecutorRegistry(shellRunner, noopSudo);
    var orchestrator =
        buildOrchestrator(
            registry, shellRunner, shellRunner, skipEvaluator, Optional.of(stateRepo), profile);

    return new ApplicationContext(
        orchestrator,
        new YamlConfigLoader(),
        Optional.empty(),
        new ParallelProbeRunner(probeRegistry),
        new ExecutionPlanBuilder(registry));
  }

  private static BootstrapOrchestratorImpl buildOrchestrator(
      PackageManagerExecutorRegistry registry,
      ShellRunner primaryRunner,
      DefaultShellRunner baseRunner,
      SkipEvaluator skipEvaluator,
      Optional<JsonStateRepository> stateRepo,
      String profile) {
    return new BootstrapOrchestratorImpl(
        registry,
        new ShellScriptExecutor(primaryRunner),
        new CompiledBinaryInstaller(baseRunner),
        new FlatpakInstaller(baseRunner),
        new FlatpakRemoteInstaller(baseRunner),
        new DotbotExecutor(baseRunner),
        new DefaultShellExecutor(primaryRunner),
        new OhMyZshExecutor(primaryRunner),
        new ToolchainExecutor(primaryRunner),
        new NerdFontExecutor(baseRunner),
        new ShellReloadExecutor(primaryRunner),
        skipEvaluator,
        stateRepo.map(r -> (dev.sysboot.core.StateRepository) r),
        profile,
        primaryRunner,
        baseRunner);
  }

  private static InstalledProbeRegistry buildProbeRegistry(ShellRunner runner) {
    return new InstalledProbeRegistry(
        List.of(
            new DnfPackageProbe(runner),
            new PacmanPackageProbe(runner),
            new AptPackageProbe(runner),
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
      boolean skipAlreadyInstalled,
      boolean reProbe) {
    Optional<dev.sysboot.core.BootstrapState> state =
        skipAlreadyInstalled && !reProbe ? stateRepo.load(profile) : Optional.empty();
    return new SkipEvaluator(state, probeRegistry, skipAlreadyInstalled, reProbe);
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
            new ZypperPackageInstaller(runner, sudo)));
  }
}
