package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.DotbotModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ScriptPath;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.ShellScriptItem;
import dev.sysboot.core.ShellScriptModule;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfiguredModuleProbeTest {

  @Test
  void catalogPreservesShellScriptProbeCommand() {
    var runner = new RecordingRunner();
    var module =
        new ShellScriptModule(
            new ModuleName("scripts"),
            List.of(
                ShellScriptItem.local(
                    new ScriptPath(Path.of("/tmp/setup.sh")), List.of(), Optional.empty())),
            Optional.empty(),
            false,
            Optional.of("test -f /tmp/setup.done"));
    var registry = new InstalledProbeRegistry(List.of(new ShellScriptProbe(runner, Map.of())));

    InstallationStatus status = registry.probe(ModuleItemCatalog.items(module).getFirst());

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
    assertThat(runner.commands)
        .containsExactly(List.of("/bin/sh", "-c", "test -f /tmp/setup.done"));
  }

  @Test
  void catalogPreservesDotbotProbeCommand() {
    var runner = new RecordingRunner();
    var module =
        new DotbotModule(
            new ModuleName("dotfiles"),
            Path.of("/tmp/dotbot.yaml"),
            "v1.0.0",
            "dotbot",
            Optional.of("test -f /tmp/dotfiles.done"));
    var registry = new InstalledProbeRegistry(List.of(new DotbotProbe(runner, Map.of())));

    InstallationStatus status = registry.probe(ModuleItemCatalog.items(module).getFirst());

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
    assertThat(runner.commands)
        .containsExactly(List.of("/bin/sh", "-c", "test -f /tmp/dotfiles.done"));
  }

  private static final class RecordingRunner implements ShellRunner {

    private final List<List<String>> commands = new ArrayList<>();

    @Override
    public ProcessResult run(
        List<String> command, Map<String, String> environment, Duration timeout) {
      commands.add(List.copyOf(command));
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
  }
}
