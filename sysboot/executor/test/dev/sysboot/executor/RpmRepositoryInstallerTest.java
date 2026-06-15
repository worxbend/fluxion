package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.RpmRepositoryModule;
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
class RpmRepositoryInstallerTest {

  @Mock private ShellRunner shellRunner;

  @Test
  void addCommand_withSigningKey_buildsAuditableDnfRepositoryCommand() {
    var installer = new RpmRepositoryInstaller(shellRunner);

    assertThat(installer.addCommand(module()))
        .containsExactly(
            "/bin/bash",
            "-lc",
            "printf %s '[example]\n"
                + "name=example\n"
                + "baseurl=https://example.test/fedora/$releasever/$basearch/stable\n"
                + "enabled=1\n"
                + "gpgcheck=1\n"
                + "gpgkey=https://example.test/key.gpg\n"
                + "' | sudo tee '/etc/yum.repos.d/example.repo' >/dev/null && sudo dnf makecache"
                + " --refresh");
  }

  @Test
  void add_whenCommandSucceeds_returnsSuccess() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(25)));
    var installer = new RpmRepositoryInstaller(shellRunner);

    StepResult result = installer.add(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  private static RpmRepositoryModule module() {
    return new RpmRepositoryModule(
        new ModuleName("example"),
        "example",
        URI.create("https://example.test/fedora/$releasever/$basearch/stable"),
        Path.of("/etc/yum.repos.d/example.repo"),
        Optional.of(URI.create("https://example.test/key.gpg")),
        true,
        true);
  }
}
