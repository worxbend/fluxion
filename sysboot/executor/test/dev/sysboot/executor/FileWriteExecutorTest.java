package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.FileWriteItem;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWriteExecutorTest {

  @Test
  void write_whenContentWithoutSudo_writesFileAndMode(@TempDir Path tempDir) throws IOException {
    Path destination = tempDir.resolve("config/tool.conf");
    var executor = new FileWriteExecutor(new CapturingRunner(), new DefaultFileWriteFileSystem());
    var item =
        new FileWriteItem(
            "tool-config",
            destination,
            Optional.of("enabled=true\n"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("0644"),
            false);

    StepResult result = executor.write(item);

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(Files.readString(destination)).isEqualTo("enabled=true\n");
  }

  @Test
  void write_whenSourceWithoutSudo_copiesFile(@TempDir Path tempDir) throws IOException {
    Path source = tempDir.resolve("source.conf");
    Path destination = tempDir.resolve("target/tool.conf");
    Files.writeString(source, "copied=true\n");
    var executor = new FileWriteExecutor(new CapturingRunner(), new DefaultFileWriteFileSystem());

    StepResult result = executor.write(sourceItem(source, destination, false));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(Files.readString(destination)).isEqualTo("copied=true\n");
  }

  @Test
  void write_whenSudoTrue_usesSudoCommandsAndDoesNotExposeContent() {
    var runner = new CapturingRunner();
    var fileSystem = new FakeFileSystem(Path.of("/tmp/staged-content"));
    var publisher = new RecordingPublisher(Path.of("/etc/.root-stage"));
    var executor = new FileWriteExecutor(runner, fileSystem, publisher);
    var item =
        new FileWriteItem(
            "sudo-config",
            Path.of("/etc/tool.conf"),
            Optional.of("secret-token=abc123\n"),
            Optional.empty(),
            Optional.of("root"),
            Optional.of("wheel"),
            Optional.of("0600"),
            true);

    StepResult result = executor.write(item);

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(fileSystem.writes()).containsExactly(Path.of("/tmp/staged-content"));
    assertThat(fileSystem.modes()).containsExactly("0600");
    assertThat(runner.commands())
        .containsExactly(
            List.of("sudo", "chown", "root:wheel", "/etc/.root-stage"),
            List.of(
                "sudo",
                TrustedSystemExecutable.move().toString(),
                "-f",
                "-T",
                "--",
                "/etc/.root-stage",
                "/etc/tool.conf"));
    assertThat(publisher.mode).isEqualTo("0600");
    assertThat(runner.commands().toString()).doesNotContain("secret-token");
  }

  @Test
  void write_whenSudoSource_stagesCallerReadableBytesBeforePrivilegeBoundary(@TempDir Path tempDir)
      throws Exception {
    var runner = new CapturingRunner();
    var fileSystem = new FakeFileSystem(Path.of("/tmp/staged-source"));
    var executor =
        new FileWriteExecutor(
            runner, fileSystem, new RecordingPublisher(Path.of("/etc/tool/.root-stage")));
    Path source = Files.writeString(tempDir.resolve("source.conf"), "source");

    StepResult result = executor.write(sourceItem(source, Path.of("/etc/tool/source.conf"), true));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(fileSystem.copies()).contains(List.of(source, Path.of("/tmp/staged-source")));
    assertThat(fileSystem.modes()).containsExactly("0600");
    assertThat(runner.commands())
        .containsExactly(
            List.of(
                "sudo",
                TrustedSystemExecutable.move().toString(),
                "-f",
                "-T",
                "--",
                "/etc/tool/.root-stage",
                "/etc/tool/source.conf"));
    assertThat(runner.commands().toString()).doesNotContain(source.toString());
  }

  @Test
  void write_whenStagedOwnershipFails_doesNotReplaceDestination() {
    var runner = new FailingChownRunner();
    var fileSystem = new FakeFileSystem(Path.of("/tmp/staged-content"));
    var publisher = new RecordingPublisher(Path.of("/etc/.root-stage"));
    var executor = new FileWriteExecutor(runner, fileSystem, publisher);
    var item =
        new FileWriteItem(
            "sudo-config",
            Path.of("/etc/tool.conf"),
            Optional.of("replacement\n"),
            Optional.empty(),
            Optional.of("root"),
            Optional.of("wheel"),
            Optional.of("0640"),
            true);

    StepResult result = executor.write(item);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(publisher.mode).isEqualTo("0640");
    assertThat(runner.commands)
        .containsExactly(List.of("sudo", "chown", "root:wheel", "/etc/.root-stage"));
  }

  @Test
  void write_whenSudoSourceIsSymlink_failsBeforePrivilegeBoundary(@TempDir Path tempDir)
      throws Exception {
    Path target = tempDir.resolve("target.conf");
    Path source = tempDir.resolve("source-link.conf");
    Files.writeString(target, "private\n");
    try {
      Files.createSymbolicLink(source, target);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
    }
    var runner = new CapturingRunner();
    var executor = new FileWriteExecutor(runner, new DefaultFileWriteFileSystem());

    StepResult result = executor.write(sourceItem(source, Path.of("/etc/tool/source.conf"), true));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("non-symbolic");
    assertThat(runner.commands()).isEmpty();
  }

  @Test
  void write_whenLocalDestinationIsSymlink_rejectsItWithoutChangingReferent(@TempDir Path tempDir)
      throws Exception {
    Path referent = tempDir.resolve("referent.conf");
    Path destination = tempDir.resolve("destination.conf");
    Files.writeString(referent, "original\n");
    try {
      Files.createSymbolicLink(destination, referent);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
    }
    var item =
        new FileWriteItem(
            "safe-write",
            destination,
            Optional.of("replacement\n"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("0600"),
            false);

    StepResult result =
        new FileWriteExecutor(new CapturingRunner(), new DefaultFileWriteFileSystem()).write(item);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(Files.readString(referent)).isEqualTo("original\n");
    assertThat(destination).isSymbolicLink();
  }

  @Test
  void write_whenLocalAncestorIsSymlink_rejectsBeforeCreatingDirectories(@TempDir Path tempDir)
      throws Exception {
    Path referent = Files.createDirectory(tempDir.resolve("referent"));
    Path linkedAncestor = tempDir.resolve("linked");
    try {
      Files.createSymbolicLink(linkedAncestor, referent);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      Assumptions.abort("Symbolic links are unavailable: " + e.getMessage());
    }
    Path destination = linkedAncestor.resolve("created/tool.conf");
    var item =
        new FileWriteItem(
            "safe-write",
            destination,
            Optional.of("replacement\n"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("0600"),
            false);

    StepResult result =
        new FileWriteExecutor(new CapturingRunner(), new DefaultFileWriteFileSystem()).write(item);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(referent.resolve("created")).doesNotExist();
  }

  @Test
  void write_whenCleanupFailsAfterCommit_preservesSuccess() {
    var fileSystem = new CleanupFailingFileSystem();
    var item =
        new FileWriteItem(
            "safe-write",
            Path.of("/tmp/output"),
            Optional.of("replacement\n"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of("0600"),
            false);

    StepResult result = new FileWriteExecutor(new CapturingRunner(), fileSystem).write(item);

    assertThat(fileSystem.committed).isTrue();
    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void dryRunCommand_previewsDestinationModeOwnershipAndSudo() {
    var executor =
        new FileWriteExecutor(new CapturingRunner(), new FakeFileSystem(Path.of("/tmp/x")));
    var item =
        new FileWriteItem(
            "config",
            Path.of("/etc/tool.conf"),
            Optional.of("value\n"),
            Optional.empty(),
            Optional.of("root"),
            Optional.of("root"),
            Optional.of("0644"),
            true);

    assertThat(executor.dryRunCommand(item))
        .containsExactly(
            "file-write",
            "/etc/tool.conf",
            "content",
            "mode",
            "0644",
            "owner",
            "root",
            "group",
            "root",
            "sudo",
            "true");
  }

  private FileWriteItem sourceItem(Path source, Path destination, boolean sudo) {
    return new FileWriteItem(
        "source-config",
        destination,
        Optional.empty(),
        Optional.of(source),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        sudo);
  }

  private static final class CapturingRunner implements ShellRunner {
    private final List<List<String>> commands = new ArrayList<>();

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(List.copyOf(command));
      String stdout =
          command.stream().anyMatch(value -> value.endsWith("/mktemp"))
              ? command.getLast().replace("XXXXXXXX", "test") + System.lineSeparator()
              : "";
      return new ProcessResult(0, stdout, "", Duration.ZERO);
    }

    List<List<String>> commands() {
      return List.copyOf(commands);
    }
  }

  private static final class FailingChownRunner implements ShellRunner {
    private final List<List<String>> commands = new ArrayList<>();

    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      commands.add(List.copyOf(command));
      return new ProcessResult(1, "", "chown failed", Duration.ZERO);
    }
  }

  private static final class FakeFileSystem implements FileWriteFileSystem {
    private final Path tempFile;
    private final List<Path> writes = new ArrayList<>();
    private final List<List<Path>> copies = new ArrayList<>();
    private final List<String> modes = new ArrayList<>();

    private FakeFileSystem(Path tempFile) {
      this.tempFile = tempFile;
    }

    @Override
    public Path createTempFile(String prefix, String suffix) {
      return tempFile;
    }

    @Override
    public void createDirectories(Path directory) {}

    @Override
    public void writeString(Path path, String content) {
      writes.add(path);
    }

    @Override
    public void copy(Path source, Path destination) {
      copies.add(List.of(source, destination));
    }

    @Override
    public void setMode(Path path, String mode) {
      modes.add(mode);
    }

    @Override
    public void deleteIfExists(Path path) {}

    List<Path> writes() {
      return List.copyOf(writes);
    }

    List<List<Path>> copies() {
      return List.copyOf(copies);
    }

    List<String> modes() {
      return List.copyOf(modes);
    }
  }

  private static final class CleanupFailingFileSystem implements FileWriteFileSystem {

    private final Path stage = Path.of("/tmp/stage");
    private boolean committed;

    @Override
    public Path createTempFile(String prefix, String suffix) {
      return stage;
    }

    @Override
    public Path createTempFile(Path directory, String prefix, String suffix) {
      return stage;
    }

    @Override
    public void createDirectories(Path directory) {}

    @Override
    public void writeString(Path path, String content) {}

    @Override
    public void copy(Path source, Path destination) {}

    @Override
    public void setMode(Path path, String mode) {}

    @Override
    public void deleteIfExists(Path path) throws IOException {
      throw new IOException("cleanup failed");
    }

    @Override
    public void atomicReplace(Path source, Path destination) {
      committed = true;
    }
  }

  private static final class RecordingPublisher implements PrivilegedArtifactPublisher {

    private final Path rootStage;
    private String mode;

    private RecordingPublisher(Path rootStage) {
      this.rootStage = rootStage;
    }

    @Override
    public ProcessResult publish(
        Path source, Path destination, String mode, Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        Sha256Digest expected,
        StagedConsumer consumer)
        throws IOException {
      this.mode = mode;
      return consumer.consume(rootStage);
    }

    @Override
    public ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }
}
