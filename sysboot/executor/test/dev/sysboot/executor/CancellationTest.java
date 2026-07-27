package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapModule;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.CancellationSignal;
import dev.sysboot.core.EventKind;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.ShellCommandItem;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * A cancelled run must stop at an item boundary, leave the machine in a known state, and record
 * where to resume. Killing the JVM mid-command gave none of those.
 */
class CancellationTest {

  @Test
  void aCancelledRunStopsBeforeTheNextItem() {
    var cancellation = new CancellationSignal();
    var executed = new ArrayList<String>();
    var events = new ArrayList<ExecutionEvent>();
    var state = new RecordingStateRepository();

    // Cancel while the first item is running, as a signal handler would.
    ShellRunner runner =
        (command, env, timeout) -> {
          executed.add(String.join(" ", command));
          cancellation.cancel();
          return new ProcessResult(0, "", "", Duration.ZERO);
        };

    orchestrator(runner, state)
        .execute(
            configWith(command("first"), command("second"), command("third")),
            events::add,
            cancellation);

    assertThat(executed).hasSize(1);
    assertThat(executed.getFirst()).contains("first");
  }

  @Test
  void aCancelledRunRecordsWhereToResume() {
    var cancellation = new CancellationSignal();
    var events = new ArrayList<ExecutionEvent>();
    var state = new RecordingStateRepository();

    ShellRunner runner =
        (command, env, timeout) -> {
          cancellation.cancel();
          return new ProcessResult(0, "", "", Duration.ZERO);
        };

    orchestrator(runner, state)
        .execute(configWith(command("first"), command("second")), events::add, cancellation);

    assertThat(state.saved.getLast().nextPlanEntry()).contains("second");
  }

  @Test
  void aCancelledRunEmitsACancelledEventNamingTheNextEntry() {
    var cancellation = new CancellationSignal();
    var events = new ArrayList<ExecutionEvent>();

    ShellRunner runner =
        (command, env, timeout) -> {
          cancellation.cancel();
          return new ProcessResult(0, "", "", Duration.ZERO);
        };

    orchestrator(runner, new RecordingStateRepository())
        .execute(configWith(command("first"), command("second")), events::add, cancellation);

    ExecutionEvent cancelled =
        events.stream()
            .filter(event -> event.kind() == EventKind.CANCELLED)
            .findFirst()
            .orElseThrow(() -> new AssertionError("no CANCELLED event was emitted"));
    assertThat(cancelled.item()).isEqualTo("second");
  }

  @Test
  void anUncancelledRunIsUnaffected() {
    var executed = new ArrayList<String>();
    ShellRunner runner =
        (command, env, timeout) -> {
          executed.add(String.join(" ", command));
          return new ProcessResult(0, "", "", Duration.ZERO);
        };

    orchestrator(runner, new RecordingStateRepository())
        .execute(
            configWith(command("first"), command("second")),
            event -> {},
            CancellationSignal.never());

    assertThat(executed).hasSize(2);
  }

  private BootstrapOrchestratorImpl orchestrator(ShellRunner runner, StateRepository state) {
    return new BootstrapOrchestratorImpl(
        new PackageManagerExecutorRegistry(List.of()),
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
        Optional.of(state),
        "test",
        runner,
        new DefaultShellRunner());
  }

  private ShellCommandModule command(String name) {
    return new ShellCommandModule(
        new ModuleName(name),
        List.of(ShellCommandItem.shell("echo " + name, "/bin/sh", Optional.empty())),
        "/bin/sh",
        Optional.empty(),
        false,
        Optional.empty());
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

  private static final class RecordingStateRepository implements StateRepository {

    private final List<BootstrapState> saved = new ArrayList<>();
    private BootstrapState current = BootstrapState.empty("test", "1.0.0");

    @Override
    public Optional<BootstrapState> load(String profileName) {
      return Optional.of(current);
    }

    @Override
    public void save(BootstrapState state) {
      current = state;
      saved.add(state);
    }

    @Override
    public BootstrapState recordSuccess(String profileName, StateEntry entry) {
      current = current.withEntry(entry);
      saved.add(current);
      return current;
    }

    @Override
    public void reset(String profileName) {
      current = BootstrapState.empty(profileName, "1.0.0");
    }

    @Override
    public Optional<BootstrapState> forgetItem(String profileName, String itemKey) {
      return Optional.of(current);
    }

    @Override
    public Optional<BootstrapState> forgetPhase(String profileName, String phaseName) {
      return Optional.of(current);
    }
  }
}
