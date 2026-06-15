package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PacmanRepositoryInstallerTest {

  @Mock private ShellRunner shellRunner;

  @Test
  void addCommand_buildsAuditablePacmanRepositoryCommand() {
    var installer = new PacmanRepositoryInstaller(shellRunner);

    assertThat(installer.addCommand(module()))
        .containsExactly(
            "/bin/bash",
            "-lc",
            "grep -Eq '^\\[example\\]$' '/etc/pacman.conf' || printf %s '\n"
                + "[example]\n"
                + "Server = https://example.test/$repo/$arch\n"
                + "SigLevel = Required DatabaseOptional\n"
                + "Include = /etc/pacman.d/example-mirrorlist\n"
                + "' | sudo tee -a '/etc/pacman.conf' >/dev/null; sudo pacman -Sy");
  }

  @Test
  void add_whenCommandSucceeds_returnsSuccess() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(25)));
    var installer = new PacmanRepositoryInstaller(shellRunner);

    StepResult result = installer.add(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  private static PacmanRepositoryModule module() {
    return new PacmanRepositoryModule(
        new ModuleName("example"),
        "example",
        URI.create("https://example.test/$repo/$arch"),
        Path.of("/etc/pacman.conf"),
        Optional.of("Required DatabaseOptional"),
        Optional.of(Path.of("/etc/pacman.d/example-mirrorlist")),
        true);
  }
}
