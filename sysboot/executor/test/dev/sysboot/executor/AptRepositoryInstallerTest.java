package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.ModuleName;
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
class AptRepositoryInstallerTest {

  @Mock private ShellRunner shellRunner;

  @Test
  void addCommand_withSigningKey_buildsAuditableAptSetupCommand() {
    var installer = new AptRepositoryInstaller(shellRunner);

    assertThat(installer.addCommand(module()))
        .containsExactly(
            "/bin/bash",
            "-lc",
            "sudo install -d -m 0755 '/etc/apt/keyrings' && curl -fsSL"
                + " 'https://example.test/key.gpg' | sudo gpg --dearmor -o"
                + " '/etc/apt/keyrings/example.gpg' && printf %s\\\\n"
                + " 'deb [signed-by=/etc/apt/keyrings/example.gpg] https://example.test/debian"
                + " stable main' | sudo tee '/etc/apt/sources.list.d/example.list' >/dev/null &&"
                + " sudo apt-get update");
  }

  @Test
  void add_whenCommandSucceeds_returnsSuccess() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(25)));
    var installer = new AptRepositoryInstaller(shellRunner);

    StepResult result = installer.add(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  private static AptRepositoryModule module() {
    return new AptRepositoryModule(
        new ModuleName("example"),
        "deb [signed-by=/etc/apt/keyrings/example.gpg] https://example.test/debian stable main",
        Path.of("/etc/apt/sources.list.d/example.list"),
        Optional.of(URI.create("https://example.test/key.gpg")),
        Optional.of(Path.of("/etc/apt/keyrings/example.gpg")));
  }
}
