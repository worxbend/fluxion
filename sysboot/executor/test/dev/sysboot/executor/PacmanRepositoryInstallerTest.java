package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.sysboot.core.ModuleName;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PacmanRepositoryInstallerTest {

  @TempDir Path tempDirectory;

  @Test
  void add_missingRepository_stagesCompleteConfigAndUsesStructuredArgv() {
    var runner = recordingRunner(exit(1), exit(0), exit(0));
    var files = new FakeConfigFiles("[options]\n");
    var publisher = new RecordingPublisher(exit(0));
    var installer = new PacmanRepositoryInstaller(runner, files, publisher);

    StepResult result = installer.add(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands)
        .containsExactly(
            List.of("grep", "-Fqx", "--", "[example]", "/etc/pacman.conf"),
            List.of("sudo", "pacman", "-Sy"));
    assertThat(publisher.destination).isEqualTo(Path.of("/etc/pacman.conf"));
    assertThat(publisher.mode).isEqualTo("0644");
    assertThat(publisher.expected)
        .isEqualTo(
            ArtifactDigests.sha256(
                files.stagedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    assertThat(files.stagedContent)
        .isEqualTo(
            """
            [options]

            [example]
            Server = https://example.test/$repo/$arch
            SigLevel = Required TrustedOnly
            Include = /etc/pacman.d/example-mirrorlist
            """);
    assertThat(files.deleted).isTrue();
  }

  @Test
  void add_existingRepository_skipsMutationAndRefreshes() {
    var runner = recordingRunner(exit(0), exit(0));
    var files = new FakeConfigFiles("[example]\n");

    StepResult result = new PacmanRepositoryInstaller(runner, files).add(module());

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(runner.commands)
        .containsExactly(
            List.of("grep", "-Fqx", "--", "[example]", "/etc/pacman.conf"),
            List.of("sudo", "pacman", "-Sy"));
    assertThat(files.stagedContent).isNull();
  }

  @Test
  void add_installFailure_stopsBeforeRefresh() {
    var runner = recordingRunner(exit(1), exit(7));
    var files = new FakeConfigFiles("[options]\n");
    var publisher = new RecordingPublisher(exit(7));

    StepResult result = new PacmanRepositoryInstaller(runner, files, publisher).add(module());

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(runner.commands).hasSize(1);
  }

  @Test
  void add_whenInfrastructureInvariantFails_doesNotConvertItToExpectedStepFailure() {
    var files =
        new FakeConfigFiles("[options]\n") {
          @Override
          public String readTrusted(Path configPath) {
            throw new IllegalStateException("broken invariant");
          }
        };

    assertThatThrownBy(
            () ->
                new PacmanRepositoryInstaller(recordingRunner(exit(0)), files, mock())
                    .add(module()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("broken invariant");
  }

  @Test
  void add_nonApprovedConfig_rejectsAtDomainBoundaryBeforeRunningCommands() throws IOException {
    Path target = Files.writeString(tempDirectory.resolve("target.conf"), "[options]\n");
    Path config = tempDirectory.resolve("pacman.conf");
    Files.createSymbolicLink(config, target);
    ShellRunner runner = mock(ShellRunner.class);

    assertThatThrownBy(() -> module(config))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("/etc/pacman.conf");
    verifyNoInteractions(runner);
  }

  @Test
  void readTrusted_rejectsOversizedConfigAndUnsafeAncestry() throws IOException {
    var files = new DefaultPacmanRepositoryConfigFiles();
    Path oversized = tempDirectory.resolve("oversized.conf");
    Files.write(oversized, new byte[(int) DefaultPacmanRepositoryConfigFiles.MAX_CONFIG_BYTES + 1]);
    Path regular = Files.writeString(tempDirectory.resolve("regular.conf"), "[options]\n");

    assertThatThrownBy(() -> files.readTrusted(oversized))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("maximum size");
    assertThatThrownBy(() -> files.readTrusted(regular))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unsafe privileged ancestry");
  }

  @Test
  void stage_createsPrivateFile() throws IOException {
    var files = new DefaultPacmanRepositoryConfigFiles();
    Path staged = files.stage("[options]\n");
    try {
      assertThat(Files.getPosixFilePermissions(staged))
          .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    } finally {
      Files.deleteIfExists(staged);
    }
  }

  private ScriptedRunner recordingRunner(ProcessResult... results) {
    return new ScriptedRunner(results);
  }

  private ProcessResult exit(int exitCode) {
    return new ProcessResult(exitCode, "", "", Duration.ZERO);
  }

  private PacmanRepositoryModule module() {
    return module(Path.of("/etc/pacman.conf"));
  }

  private PacmanRepositoryModule module(Path config) {
    return new PacmanRepositoryModule(
        new ModuleName("example"),
        "example",
        URI.create("https://example.test/$repo/$arch"),
        config,
        Optional.of("Required TrustedOnly"),
        Optional.of(Path.of("/etc/pacman.d/example-mirrorlist")),
        true);
  }

  private static final class ScriptedRunner implements ShellRunner {

    private final List<ProcessResult> results;
    private final List<List<String>> commands = new ArrayList<>();
    private int index;

    private ScriptedRunner(ProcessResult... results) {
      this.results = List.of(results);
    }

    @Override
    public ProcessResult run(
        List<String> command, java.util.Map<String, String> environment, Duration timeout) {
      commands.add(command);
      return results.get(index++);
    }
  }

  private static class FakeConfigFiles implements PacmanRepositoryConfigFiles {

    private final String current;
    private final Path staged = Path.of("/tmp/staged-pacman.conf");
    private String stagedContent;
    private boolean deleted;

    private FakeConfigFiles(String current) {
      this.current = current;
    }

    @Override
    public String readTrusted(Path configPath) {
      return current;
    }

    @Override
    public Path stage(String content) {
      stagedContent = content;
      return staged;
    }

    @Override
    public void deleteIfExists(Path path) {
      deleted = true;
    }
  }

  private static final class RecordingPublisher implements PrivilegedArtifactPublisher {

    private final ProcessResult result;
    private Path destination;
    private String mode;
    private Sha256Digest expected;

    private RecordingPublisher(ProcessResult result) {
      this.result = result;
    }

    @Override
    public ProcessResult publish(
        Path source, Path destination, String mode, Sha256Digest expected) {
      this.destination = destination;
      this.mode = mode;
      this.expected = expected;
      return result;
    }

    @Override
    public ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        Sha256Digest expected,
        StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }
}
