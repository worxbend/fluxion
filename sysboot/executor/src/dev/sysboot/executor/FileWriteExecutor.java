package dev.sysboot.executor;

import dev.sysboot.core.FileWriteItem;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FileWriteExecutor {

  private static final Duration SUDO_TIMEOUT = Duration.ofMinutes(1);

  private final ShellRunner shellRunner;
  private final FileWriteFileSystem fileSystem;

  public FileWriteExecutor(ShellRunner shellRunner) {
    this(shellRunner, new DefaultFileWriteFileSystem());
  }

  FileWriteExecutor(ShellRunner shellRunner, FileWriteFileSystem fileSystem) {
    this.shellRunner = shellRunner;
    this.fileSystem = fileSystem;
  }

  public StepResult write(FileWriteItem item) {
    Instant start = Instant.now();
    try {
      writeItem(item);
      return new StepResult.Success(item.itemKey(), Duration.between(start, Instant.now()));
    } catch (IOException e) {
      return new StepResult.Failure(
          item.itemKey(), e.getMessage(), 1, Duration.between(start, Instant.now()));
    }
  }

  public List<String> dryRunCommand(FileWriteItem item) {
    var preview = new ArrayList<String>();
    preview.addAll(List.of("file-write", item.destination().toString()));
    preview.add(item.content().isPresent() ? "content" : "source");
    item.source().map(Path::toString).ifPresent(preview::add);
    appendPreview("mode", item.mode(), preview);
    appendPreview("owner", item.owner(), preview);
    appendPreview("group", item.group(), preview);
    preview.addAll(List.of("sudo", Boolean.toString(item.sudo())));
    return List.copyOf(preview);
  }

  public List<dev.sysboot.core.ModuleItem> items(FileWriteModule module) {
    return module.items().stream()
        .map(item -> new dev.sysboot.core.ModuleItem(
            module.name(), item.itemKey(), item.name(), dev.sysboot.core.ItemType.FILE_WRITE,
            Optional.empty()))
        .toList();
  }

  private void writeItem(FileWriteItem item) throws IOException {
    if (item.sudo()) {
      writeWithSudo(item);
      return;
    }
    writeWithoutSudo(item);
  }

  private void writeWithoutSudo(FileWriteItem item) throws IOException {
    createParent(item.destination());
    if (item.content().isPresent()) {
      fileSystem.writeString(item.destination(), item.content().orElseThrow());
    } else {
      fileSystem.copy(item.source().orElseThrow(), item.destination());
    }
    applyLocalMode(item);
    applyOwnership(item, false);
  }

  private void writeWithSudo(FileWriteItem item) throws IOException {
    Path staged = stageSource(item);
    try {
      Path parent = item.destination().getParent();
      if (parent != null) {
        runCommand(sudo("mkdir", "-p", parent.toString()));
      }
      runCommand(sudo("cp", staged.toString(), item.destination().toString()));
      if (item.mode().isPresent()) {
        runCommand(sudo("chmod", item.mode().orElseThrow(), item.destination().toString()));
      }
      applyOwnership(item, true);
    } finally {
      if (item.source().filter(staged::equals).isEmpty()) {
        fileSystem.deleteIfExists(staged);
      }
    }
  }

  private Path stageSource(FileWriteItem item) throws IOException {
    if (item.source().isPresent()) {
      return item.source().orElseThrow();
    }
    Path staged = fileSystem.createTempFile("fluxion-file-write-", ".tmp");
    fileSystem.writeString(staged, item.content().orElseThrow());
    return staged;
  }

  private void applyLocalMode(FileWriteItem item) throws IOException {
    if (item.mode().isPresent()) {
      fileSystem.setMode(item.destination(), item.mode().orElseThrow());
    }
  }

  private void applyOwnership(FileWriteItem item, boolean sudo) throws IOException {
    if (item.owner().isEmpty() && item.group().isEmpty()) {
      return;
    }
    runCommand(chownCommand(item, sudo));
  }

  private List<String> chownCommand(FileWriteItem item, boolean sudo) {
    String ownerGroup = item.owner().orElse("") + item.group().map(group -> ":" + group).orElse("");
    return sudo ? sudo("chown", ownerGroup, item.destination().toString())
        : List.of("chown", ownerGroup, item.destination().toString());
  }

  private void createParent(Path destination) throws IOException {
    Path parent = destination.getParent();
    if (parent != null) {
      fileSystem.createDirectories(parent);
    }
  }

  private void runCommand(List<String> command) throws IOException {
    ProcessResult result = shellRunner.run(command, Map.of(), SUDO_TIMEOUT);
    if (result.exitCode() != 0) {
      throw new IOException("Command failed: " + String.join(" ", command));
    }
  }

  private List<String> sudo(String command, String... args) {
    var values = new ArrayList<String>();
    values.add("sudo");
    values.add(command);
    values.addAll(List.of(args));
    return List.copyOf(values);
  }

  private void appendPreview(String label, Optional<String> value, List<String> preview) {
    preview.add(label);
    preview.add(value.orElse("<unchanged>"));
  }
}
