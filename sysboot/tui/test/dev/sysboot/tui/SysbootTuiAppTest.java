package dev.sysboot.tui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ExecutionPausedException;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.StepResult;
import dev.sysboot.executor.ExecutionCancelledException;
import dev.sysboot.executor.ShellExecutionException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SysbootTuiAppTest {

  @Test
  void run_rendersExecutionFramesBeforeCompletion() throws Exception {
    var out = new ByteArrayOutputStream();
    var app =
        new SysbootTuiApp(
            new DelayedOrchestrator(),
            new TuiExecutionEventListener(),
            new TuiSudoPasswordProvider(),
            List.of(),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());

    app.run(config(), false);

    String rendered = out.toString(StandardCharsets.UTF_8);
    assertThat(rendered)
        .contains("fluxion-test [0%]")
        .contains("git")
        .contains("RUNNING")
        .contains("Bootstrap Complete")
        .contains("Completed: 1");
  }

  @Test
  void run_whenProfileDefaultsToDryRun_usesDryRunAfterSelection() throws Exception {
    var orchestrator = new RecordingOrchestrator();
    var app =
        new SysbootTuiApp(
            orchestrator,
            new TuiExecutionEventListener(),
            new TuiSudoPasswordProvider(),
            List.of(),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());

    app.run(configWithDryRunDefault(), false);

    assertThat(orchestrator.dryRunCalled).isTrue();
    assertThat(orchestrator.executeCalled).isFalse();
  }

  @Test
  void run_whenOrchestratorLaunchFails_preservesShellFailureClassification() {
    var app =
        new SysbootTuiApp(
            new FailingOrchestrator(),
            new TuiExecutionEventListener(),
            new TuiSudoPasswordProvider(),
            List.of(),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> app.run(config(), false))
        .isInstanceOf(ShellExecutionException.class)
        .hasMessage("failed to start dnf");
  }

  @Test
  void run_whenOrchestratorThrowsError_doesNotRenderSuccessfulCompletion() {
    var out = new ByteArrayOutputStream();
    AssertionError failure = new AssertionError("fatal worker failure");
    var app =
        new SysbootTuiApp(
            new ErrorOrchestrator(failure),
            new TuiExecutionEventListener(),
            new TuiSudoPasswordProvider(),
            List.of(),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());

    assertThatThrownBy(() -> app.run(config(), false)).isSameAs(failure);
    assertThat(app.currentState()).isNotInstanceOf(AppState.Completed.class);
    assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("Bootstrap Complete");
  }

  @Test
  void run_whenRendererIsInterrupted_interruptsWorkerAndClassifiesCancellation() throws Exception {
    var orchestrator = new InterruptibleOrchestrator();
    var app =
        new SysbootTuiApp(
            orchestrator,
            new TuiExecutionEventListener(),
            new TuiSudoPasswordProvider(),
            List.of(),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());
    var failure = new AtomicReference<Throwable>();
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    app.run(config(), false);
                  } catch (Throwable throwable) {
                    failure.set(throwable);
                  }
                });

    await(orchestrator.entered);
    caller.interrupt();
    caller.join(Duration.ofSeconds(2));

    assertThat(orchestrator.interrupted.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isInstanceOf(ExecutionCancelledException.class);
    assertThat(app.currentState()).isNotInstanceOf(AppState.Completed.class);
  }

  @Test
  void run_whenExecutionPauses_doesNotRenderSuccessfulCompletion() {
    var out = new ByteArrayOutputStream();
    var app =
        new SysbootTuiApp(
            new PausingOrchestrator(),
            new TuiExecutionEventListener(),
            new TuiSudoPasswordProvider(),
            List.of(),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());

    assertThatThrownBy(() -> app.run(config(), false))
        .isInstanceOf(ExecutionPausedException.class)
        .satisfies(
            error -> assertThat(((ExecutionPausedException) error).exitCode()).isEqualTo(42));
    assertThat(app.currentState()).isNotInstanceOf(AppState.Completed.class);
    assertThat(out.toString(StandardCharsets.UTF_8)).doesNotContain("Bootstrap Complete");
  }

  @Test
  void run_whenExecutionIsFiltered_preservesFullManifestForStateIdentity() throws Exception {
    var orchestrator = new RecordingOrchestrator();
    var app =
        new SysbootTuiApp(
            orchestrator,
            new TuiExecutionEventListener(),
            new TuiSudoPasswordProvider(),
            List.of(),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());
    BootstrapConfig manifest = phasedConfig("base", "desktop");
    BootstrapConfig filtered = phasedConfig("desktop");

    app.run(manifest, filtered, false, CancellationSignal.never());

    assertThat(orchestrator.manifestConfig).isSameAs(manifest);
    assertThat(orchestrator.executionPhases)
        .extracting(phase -> phase.name().value())
        .containsExactly("desktop");
  }

  @Test
  void run_whenSelectionIsQuitAtAnyDepth_throwsTypedCancellationWithoutExecution() {
    List<List<String>> commandPaths =
        List.of(
            List.of("q"),
            List.of("quit"),
            List.of("s 1", "q"),
            List.of("s 1", "quit"),
            List.of("s 1", "e 1", "q"),
            List.of("s 1", "e 1", "quit"));

    for (List<String> commandPath : commandPaths) {
      var orchestrator = new RecordingOrchestrator();
      var app =
          new SysbootTuiApp(
              orchestrator,
              new TuiExecutionEventListener(),
              new TuiSudoPasswordProvider(),
              List.of(),
              new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
              Duration.ofMillis(5),
              selectionPrompt(commandPath));

      assertThatThrownBy(() -> app.run(config(), false))
          .as("selection commands %s", commandPath)
          .isInstanceOf(ExecutionCancelledException.class);
      assertThat(orchestrator.executeCalled).isFalse();
      assertThat(orchestrator.dryRunCalled).isFalse();
    }
  }

  @Test
  void run_whileConsolePasswordReadIsPending_rendersSudoPromptAndHidesPassword() throws Exception {
    var out = new ByteArrayOutputStream();
    var reader = new PromptingReader();
    var provider = new TuiSudoPasswordProvider(reader);
    var app =
        new SysbootTuiApp(
            new PromptingOrchestrator(provider),
            new TuiExecutionEventListener(),
            provider,
            List.of(),
            new PrintStream(out, true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());
    Thread release =
        Thread.ofVirtual()
            .start(
                () -> {
                  await(reader.entered);
                  Instant deadline = Instant.now().plusSeconds(2);
                  while (!out.toString(StandardCharsets.UTF_8).contains("Sudo password required")
                      && Instant.now().isBefore(deadline)) {
                    Thread.yield();
                  }
                  reader.release.countDown();
                });

    app.run(config(), false);
    release.join();

    assertThat(out.toString(StandardCharsets.UTF_8))
        .contains("Sudo password required")
        .doesNotContain("secret");
    assertThat(app.currentState()).isInstanceOf(AppState.Completed.class);
  }

  @Test
  void runPrivilegePreflight_afterPromptCompletes_restoresPreviousState() throws Exception {
    var reader = new PromptingReader();
    var provider = new TuiSudoPasswordProvider(reader);
    var app =
        new SysbootTuiApp(
            new RecordingOrchestrator(),
            new TuiExecutionEventListener(),
            provider,
            List.of("workstation"),
            new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
            Duration.ofMillis(5),
            TuiSelectionPrompt.autoSelect());
    Thread preflight =
        Thread.ofVirtual()
            .start(
                () ->
                    app.runPrivilegePreflight(
                        () ->
                            provider
                                .requestPassword("authenticate sudo")
                                .ifPresent(password -> Arrays.fill(password, '\0'))));

    await(reader.entered);
    awaitState(app, AppState.SudoPrompt.class);
    reader.release.countDown();
    preflight.join();

    assertThat(app.currentState()).isInstanceOf(AppState.Dashboard.class);
  }

  private BootstrapConfig config() {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("fluxion-test"))
        .target(new OsTarget.FedoraTarget("40"))
        .addModule(
            new PackageModule(
                new ModuleName("base"),
                PackageManagerKind.DNF,
                List.of(new PackageName("git")),
                false))
        .build();
  }

  private BootstrapConfig configWithDryRunDefault() {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("fluxion-test"))
        .target(new OsTarget.FedoraTarget("40"))
        .policy(new BootstrapPolicy(Optional.of(true), Optional.empty(), Optional.empty()))
        .addModule(
            new PackageModule(
                new ModuleName("base"),
                PackageManagerKind.DNF,
                List.of(new PackageName("git")),
                false))
        .build();
  }

  private TuiSelectionPrompt selectionPrompt(List<String> commands) {
    var remaining = new ArrayDeque<>(commands);
    var reader =
        new TuiSelectionPrompt.LineReader() {
          @Override
          public boolean available() {
            return true;
          }

          @Override
          public String readLine(String prompt) {
            return Optional.ofNullable(remaining.poll()).orElse("run");
          }
        };
    return new TuiSelectionPrompt(
        reader,
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
        new BootstrapConfigSelectionFilter());
  }

  private BootstrapConfig phasedConfig(String... phaseNames) {
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName("fluxion-test"))
            .target(new OsTarget.FedoraTarget("40"));
    for (String phaseName : phaseNames) {
      builder.addPhase(
          new Phase(
              new PhaseName(phaseName),
              "",
              List.of(
                  new PackageModule(
                      new ModuleName(phaseName + "-packages"),
                      PackageManagerKind.DNF,
                      List.of(new PackageName(phaseName + "-package")),
                      false)),
              List.of(),
              new RestartPolicy.None()));
    }
    return builder.build();
  }

  private static final class RecordingOrchestrator implements BootstrapOrchestrator {

    private boolean executeCalled;
    private boolean dryRunCalled;
    private BootstrapConfig manifestConfig;
    private List<Phase> executionPhases = List.of();

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
      executeCalled = true;
    }

    @Override
    public void execute(
        BootstrapConfig config,
        List<Phase> executionPhases,
        ExecutionEventListener listener,
        CancellationSignal cancellation) {
      executeCalled = true;
      manifestConfig = config;
      this.executionPhases = List.copyOf(executionPhases);
    }

    @Override
    public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
      dryRunCalled = true;
    }
  }

  private static final class DelayedOrchestrator implements BootstrapOrchestrator {

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
      ModuleName module = config.modules().getFirst().name();
      listener.onEvent(ExecutionEvent.moduleStarted(module));
      listener.onEvent(ExecutionEvent.itemStarted(module, "git"));
      sleep();
      listener.onEvent(
          ExecutionEvent.itemCompleted(
              module, "git", new StepResult.Success("git", Duration.ofMillis(20))));
      listener.onEvent(ExecutionEvent.moduleCompleted(module));
    }

    @Override
    public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
      execute(config, listener);
    }

    private void sleep() {
      try {
        Thread.sleep(Duration.ofMillis(30));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class FailingOrchestrator implements BootstrapOrchestrator {

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
      throw new ShellExecutionException("failed to start dnf");
    }

    @Override
    public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
      throw new ShellExecutionException("failed to start dnf");
    }
  }

  private static final class ErrorOrchestrator implements BootstrapOrchestrator {

    private final Error failure;

    private ErrorOrchestrator(Error failure) {
      this.failure = failure;
    }

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
      throw failure;
    }

    @Override
    public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
      throw failure;
    }
  }

  private static final class PausingOrchestrator implements BootstrapOrchestrator {

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
      throw new ExecutionPausedException("pause", "pause requested", Optional.empty(), 42);
    }

    @Override
    public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
      execute(config, listener);
    }
  }

  private static final class InterruptibleOrchestrator implements BootstrapOrchestrator {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch interrupted = new CountDownLatch(1);

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
      entered.countDown();
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException e) {
        interrupted.countDown();
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {
      execute(config, listener);
    }
  }

  private static final class PromptingOrchestrator implements BootstrapOrchestrator {

    private final TuiSudoPasswordProvider provider;

    private PromptingOrchestrator(TuiSudoPasswordProvider provider) {
      this.provider = provider;
    }

    @Override
    public void execute(BootstrapConfig config, ExecutionEventListener listener) {
      provider
          .requestPassword("authenticate sudo")
          .ifPresent(password -> Arrays.fill(password, '\0'));
    }

    @Override
    public void dryRun(BootstrapConfig config, ExecutionEventListener listener) {}
  }

  private static final class PromptingReader implements TuiSudoPasswordProvider.PasswordReader {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public char[] readPassword(String prompt) {
      entered.countDown();
      await(release);
      return "secret".toCharArray();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void awaitState(SysbootTuiApp app, Class<? extends AppState> stateType) {
    Instant deadline = Instant.now().plusSeconds(2);
    while (!stateType.isInstance(app.currentState()) && Instant.now().isBefore(deadline)) {
      Thread.yield();
    }
    assertThat(app.currentState()).isInstanceOf(stateType);
  }
}
