package dev.sysboot.tui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapOrchestrator;
import dev.sysboot.core.ExecutionEvent;
import dev.sysboot.core.ExecutionEventListener;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.StepResult;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
}
