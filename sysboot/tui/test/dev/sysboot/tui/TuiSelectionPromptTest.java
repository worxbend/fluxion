package dev.sysboot.tui;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiSelectionPromptTest {

  @Test
  void select_allowsDrillingIntoStepsAndEntries() {
    var prompt = prompt("s 1", "e 1", "t 2", "b", "b", "j 2", "run");

    BootstrapConfig selected = prompt.select(config()).orElseThrow();

    assertThat(selected.phases()).hasSize(1);
    assertThat(selected.phases().getFirst().name().value()).isEqualTo("base");
    var module = (PackageModule) selected.phases().getFirst().modules().getFirst();
    assertThat(module.packages()).extracting(PackageName::value).containsExactly("git");
  }

  @Test
  void select_removesDependenciesOnSkippedJobs() {
    var prompt = prompt("j 1", "run");

    BootstrapConfig selected = prompt.select(config()).orElseThrow();

    assertThat(selected.phases()).hasSize(1);
    assertThat(selected.phases().getFirst().name().value()).isEqualTo("desktop");
    assertThat(selected.phases().getFirst().dependsOn()).isEmpty();
  }

  private TuiSelectionPrompt prompt(String... commands) {
    return new TuiSelectionPrompt(
        new ScriptedLineReader(commands),
        new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
        new BootstrapConfigSelectionFilter());
  }

  private BootstrapConfig config() {
    return BootstrapConfig.builder()
        .profileName(new ProfileName("selection-test"))
        .target(new OsTarget.FedoraTarget("40"))
        .addPhase(
            new Phase(
                new PhaseName("base"),
                "Base packages",
                List.of(
                    new PackageModule(
                        new ModuleName("packages"),
                        PackageManagerKind.DNF,
                        List.of(new PackageName("git"), new PackageName("curl")),
                        false)),
                List.of(),
                new RestartPolicy.None()))
        .addPhase(
            new Phase(
                new PhaseName("desktop"),
                "Desktop apps",
                List.of(
                    new FlatpakModule(
                        new ModuleName("flatpaks"),
                        "flathub",
                        List.of("org.mozilla.firefox", "com.slack.Slack"))),
                List.of(new PhaseName("base")),
                new RestartPolicy.None()))
        .build();
  }

  private static final class ScriptedLineReader implements TuiSelectionPrompt.LineReader {

    private final ArrayDeque<String> commands;

    ScriptedLineReader(String... commands) {
      this.commands = new ArrayDeque<>(List.of(commands));
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public String readLine(String prompt) {
      return Optional.ofNullable(commands.poll()).orElse("run");
    }
  }
}
