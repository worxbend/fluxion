package dev.sysboot.executor;

import dev.sysboot.core.FileWriteItem;
import dev.sysboot.core.FileWriteModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
  private final PrivilegedArtifactPublisher publisher;

  public FileWriteExecutor(ShellRunner shellRunner) {
    this(
        shellRunner,
        new DefaultFileWriteFileSystem(),
        new PrivilegedAtomicFilePublisher(shellRunner));
  }

  FileWriteExecutor(ShellRunner shellRunner, FileWriteFileSystem fileSystem) {
    this(shellRunner, fileSystem, new PrivilegedAtomicFilePublisher(shellRunner));
  }

  FileWriteExecutor(
      ShellRunner shellRunner,
      FileWriteFileSystem fileSystem,
      PrivilegedArtifactPublisher publisher) {
    this.shellRunner = shellRunner;
    this.fileSystem = fileSystem;
    this.publisher = publisher;
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
        .map(
            item ->
                new dev.sysboot.core.ModuleItem(
                    module.name(),
                    item.itemKey(),
                    item.name(),
                    dev.sysboot.core.ItemType.FILE_WRITE,
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
    fileSystem.requireSafeDestination(item.destination(), false);
    createParent(item.destination());
    fileSystem.requireSafeDestination(item.destination(), false);
    Path staged =
        fileSystem.createTempFile(item.destination().getParent(), ".fluxion-file-write-", ".tmp");
    try {
      populateStage(item, staged);
      fileSystem.preserveMode(item.destination(), staged);
      applyLocalMode(item, staged);
      applyOwnership(item, false, staged);
      fileSystem.requireSafeDestination(item.destination(), false);
      fileSystem.atomicReplace(staged, item.destination());
    } finally {
      deleteLocalStage(staged);
    }
  }

  private void writeWithSudo(FileWriteItem item) throws IOException {
    Path staged = stageSource(item);
    try {
      fileSystem.requireSafeDestination(item.destination(), true);
      ProcessResult result =
          publisher.consumeVerified(
              staged,
              item.destination(),
              item.mode().orElse("0600"),
              approvedDigest(item),
              rootStage -> commitPrivileged(item, rootStage));
      if (!result.isSuccess()) {
        throw new IOException("Privileged file publication failed");
      }
    } finally {
      deleteLocalStage(staged);
    }
  }

  private void populateStage(FileWriteItem item, Path staged) throws IOException {
    if (item.source().isPresent()) {
      fileSystem.copyReadableRegularFile(item.source().orElseThrow(), staged);
    } else {
      fileSystem.writeString(staged, item.content().orElseThrow());
    }
  }

  private ProcessResult commitPrivileged(FileWriteItem item, Path rootStage) throws IOException {
    applyStagedOwnership(item, rootStage);
    fileSystem.requireSafeDestination(item.destination(), true);
    return runCommand(
        sudo(
            TrustedSystemExecutable.move().toString(),
            "-f",
            "-T",
            "--",
            rootStage.toString(),
            item.destination().toString()));
  }

  private void applyStagedOwnership(FileWriteItem item, Path rootStage) throws IOException {
    if (item.owner().isEmpty() && item.group().isEmpty()) {
      return;
    }
    runCommand(chownCommand(item, true, rootStage));
  }

  private dev.sysboot.core.Sha256Digest approvedDigest(FileWriteItem item) throws IOException {
    if (item.content().isPresent()) {
      return ArtifactDigests.sha256(item.content().orElseThrow().getBytes(StandardCharsets.UTF_8));
    }
    return ArtifactDigests.sha256(item.source().orElseThrow());
  }

  private void deleteLocalStage(Path staged) {
    try {
      fileSystem.deleteIfExists(staged);
    } catch (IOException ignored) {
      // Cleanup must not replace the authoritative publication result.
    }
  }

  private Path stageSource(FileWriteItem item) throws IOException {
    Path staged = fileSystem.createTempFile("fluxion-file-write-", ".tmp");
    try {
      if (item.source().isPresent()) {
        fileSystem.copyReadableRegularFile(item.source().orElseThrow(), staged);
      } else {
        fileSystem.writeString(staged, item.content().orElseThrow());
      }
      fileSystem.setMode(staged, "0600");
      return staged;
    } catch (IOException e) {
      deleteLocalStage(staged);
      throw e;
    }
  }

  private void applyLocalMode(FileWriteItem item, Path destination) throws IOException {
    if (item.mode().isPresent()) {
      fileSystem.setMode(destination, item.mode().orElseThrow());
    }
  }

  private void applyOwnership(FileWriteItem item, boolean sudo, Path destination)
      throws IOException {
    if (item.owner().isEmpty() && item.group().isEmpty()) {
      return;
    }
    runCommand(chownCommand(item, sudo, destination));
  }

  private List<String> chownCommand(FileWriteItem item, boolean sudo, Path destination) {
    String ownerGroup = item.owner().orElse("") + item.group().map(group -> ":" + group).orElse("");
    return sudo
        ? sudo("chown", ownerGroup, destination.toString())
        : List.of("chown", ownerGroup, destination.toString());
  }

  private void createParent(Path destination) throws IOException {
    Path parent = destination.getParent();
    if (parent != null) {
      fileSystem.createDirectories(parent);
    }
  }

  private ProcessResult runCommand(List<String> command) throws IOException {
    ProcessResult result = shellRunner.run(command, Map.of(), SUDO_TIMEOUT);
    if (result.exitCode() != 0) {
      throw new IOException("Command failed: " + String.join(" ", command));
    }
    return result;
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
