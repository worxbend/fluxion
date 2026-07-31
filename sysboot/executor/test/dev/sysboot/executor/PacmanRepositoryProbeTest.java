package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PacmanRepositoryProbeTest {

  @Mock private ShellRunner shellRunner;

  @Test
  void supports_pacmanRepositoryType_returnsTrue() {
    assertThat(new PacmanRepositoryProbe(shellRunner).supports(ItemType.PACMAN_REPOSITORY))
        .isTrue();
  }

  @Test
  void probe_whenRepositoryExists_returnsInstalledByProbe() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(0, "", "", Duration.ofMillis(5)));

    InstallationStatus status = new PacmanRepositoryProbe(shellRunner).probe("chaotic-aur");

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @Test
  void probe_whenRepositoryIsMissing_returnsNotInstalled() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "", Duration.ofMillis(5)));

    InstallationStatus status = new PacmanRepositoryProbe(shellRunner).probe("chaotic-aur");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @Test
  void probe_whenNameContainsRegexMetacharacters_usesLiteralFixedStringMatch() {
    when(shellRunner.run(any(), any(), any()))
        .thenReturn(new ProcessResult(1, "", "", Duration.ofMillis(5)));

    InstallationStatus status = new PacmanRepositoryProbe(shellRunner).probe("foo.*");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
    verify(shellRunner)
        .run(
            eq(List.of("grep", "-Fqx", "--", "[foo.*]", "/etc/pacman.conf")),
            eq(Map.of()),
            eq(Duration.ofSeconds(15)));
  }

  @Test
  void probe_whenConfigHasRegexLikeNearMatch_doesNotMatchIt(@TempDir Path tempDir)
      throws IOException {
    Path config = tempDir.resolve("pacman.conf");
    Files.writeString(config, "[foobar]\n", StandardCharsets.UTF_8);
    var probe = new PacmanRepositoryProbe(new DefaultShellRunner(), config);

    InstallationStatus status = probe.probe("foo.*");

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }
}
