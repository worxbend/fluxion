package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.FileWriteItem;
import dev.sysboot.core.ProcessResult;
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
    var executor = new FileWriteExecutor(runner, fileSystem);
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
    assertThat(runner.commands())
        .containsExactly(
            List.of("sudo", "mkdir", "-p", "/etc"),
            List.of("sudo", "cp", "/tmp/staged-content", "/etc/tool.conf"),
            List.of("sudo", "chmod", "0600", "/etc/tool.conf"),
            List.of("sudo", "chown", "root:wheel", "/etc/tool.conf"));
    assertThat(runner.commands().toString()).doesNotContain("secret-token");
  }

  @Test
  void dryRunCommand_previewsDestinationModeOwnershipAndSudo() {
    var executor = new FileWriteExecutor(new CapturingRunner(), new FakeFileSystem(Path.of("/tmp/x")));
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
      return new ProcessResult(0, "", "", Duration.ZERO);
    }

    List<List<String>> commands() {
      return List.copyOf(commands);
    }
  }

  private static final class FakeFileSystem implements FileWriteFileSystem {
    private final Path tempFile;
    private final List<Path> writes = new ArrayList<>();

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
    public void copy(Path source, Path destination) {}

    @Override
    public void setMode(Path path, String mode) {}

    @Override
    public void deleteIfExists(Path path) {}

    List<Path> writes() {
      return List.copyOf(writes);
    }
  }
}
