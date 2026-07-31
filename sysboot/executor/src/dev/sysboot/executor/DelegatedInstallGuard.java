package dev.sysboot.executor;

import dev.sysboot.core.CompiledBinaryModule;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DelegatedInstallGuard {

  private static final Logger log = LoggerFactory.getLogger(DelegatedInstallGuard.class);

  private final BinaryFileSystem fileSystem;

  DelegatedInstallGuard(BinaryFileSystem fileSystem) {
    this.fileSystem = fileSystem;
  }

  Snapshot prepare(CompiledBinaryModule module) throws IOException {
    Path canonical = BinaryProfileTranslator.appsBinaryPath(module);
    var targets = new LinkedHashMap<Path, Boolean>();
    Path binaryDirectory = parent(canonical);
    Path toolDirectory = parent(binaryDirectory);
    Path appsDirectory = parent(toolDirectory);
    targets.put(canonical, true);
    targets.putIfAbsent(module.installPath(), true);
    module.symlinkPath().ifPresent(path -> targets.putIfAbsent(path, true));
    return prepare(List.copyOf(targets.keySet()), parent(appsDirectory));
  }

  Snapshot prepare(List<Path> paths) throws IOException {
    return prepare(paths.stream().distinct().toList(), commonParent(paths));
  }

  private Snapshot prepare(List<Path> targets, Path backupRoot) throws IOException {
    requireDisjointTargets(targets);
    Path backupDirectory = fileSystem.createTempDirectory(backupRoot, ".sysboot-delegate-backups-");
    var entries = new ArrayList<Entry>();
    try {
      requireExternalBackup(targets, backupDirectory);
      for (Path target : targets) {
        fileSystem.requireNoSymlinkAncestors(target);
        entries.add(snapshot(target, backupDirectory));
      }
      return new Snapshot(List.copyOf(entries), backupDirectory);
    } catch (IOException | RuntimeException failure) {
      discardBackups(backupDirectory, failure);
      throw failure;
    }
  }

  void restore(Snapshot snapshot) throws IOException {
    IOException failure = null;
    for (Entry entry : snapshot.entries().reversed()) {
      try {
        restore(entry);
      } catch (IOException | RuntimeException restoreFailure) {
        failure = accumulate(failure, entry.path(), restoreFailure);
      }
    }
    if (failure == null) {
      try {
        fileSystem.deleteTreeIfExists(snapshot.backupDirectory());
      } catch (IOException | RuntimeException cleanupFailure) {
        failure = accumulate(failure, snapshot.backupDirectory(), cleanupFailure);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  void commit(Snapshot snapshot) {
    try {
      fileSystem.deleteTreeIfExists(snapshot.backupDirectory());
    } catch (IOException | RuntimeException failure) {
      log.warn(
          "Failed to delete delegated-install backup directory: {}", snapshot.backupDirectory());
    }
  }

  private Entry snapshot(Path path, Path backupDirectory) throws IOException {
    if (fileSystem.isSymbolicLink(path)) {
      return Entry.symlink(path, fileSystem.readSymbolicLink(path));
    }
    if (!fileSystem.pathEntryExists(path)) {
      return Entry.absent(path);
    }
    if (!fileSystem.isRegularFile(path)) {
      throw new IOException("Refusing to delegate over non-regular output: " + path);
    }
    Path backup = backupDirectory.resolve(".sysboot-delegate-backup-" + UUID.randomUUID());
    fileSystem.copyWithAttributes(path, backup);
    return Entry.regular(path, backup);
  }

  private void restore(Entry entry) throws IOException {
    fileSystem.requireNoSymlinkAncestors(entry.path());
    fileSystem.deleteTreeIfExists(entry.path());
    switch (entry.kind()) {
      case ABSENT -> {}
      case REGULAR -> restoreRegular(entry);
      case SYMLINK -> {
        fileSystem.createDirectories(parent(entry.path()));
        fileSystem.createSymlink(entry.path(), entry.linkTarget().orElseThrow());
      }
    }
  }

  private void restoreRegular(Entry entry) throws IOException {
    Path directory = parent(entry.path());
    fileSystem.createDirectories(directory);
    Path staged = fileSystem.createTempFile(directory, ".sysboot-delegate-restore-", ".tmp");
    Throwable primaryFailure = null;
    try {
      fileSystem.copyWithAttributes(entry.backup().orElseThrow(), staged);
      fileSystem.atomicMoveReplace(staged, entry.path());
    } catch (IOException | RuntimeException failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      FailurePreservingCleanup.run(primaryFailure, () -> fileSystem.deleteIfExists(staged));
    }
  }

  private void requireDisjointTargets(List<Path> targets) throws IOException {
    List<Path> resolved = new ArrayList<>(targets.size());
    for (Path target : targets) {
      resolved.add(resolvePhysicalEntry(target));
    }
    for (int left = 0; left < resolved.size(); left++) {
      for (int right = left + 1; right < resolved.size(); right++) {
        Path first = resolved.get(left);
        Path second = resolved.get(right);
        if (first.startsWith(second) || second.startsWith(first)) {
          throw new IOException(
              "Refusing overlapping delegated outputs: " + first + " and " + second);
        }
      }
    }
  }

  private void requireExternalBackup(List<Path> targets, Path backupDirectory) throws IOException {
    if (backupDirectory == null) {
      return;
    }
    Path resolvedBackup = resolvePhysicalEntry(backupDirectory);
    for (Path target : targets) {
      Path resolvedTarget = resolvePhysicalEntry(target);
      if (resolvedBackup.startsWith(resolvedTarget)) {
        throw new IOException("Delegated backup must be outside managed output: " + target);
      }
    }
  }

  private Path resolvePhysicalEntry(Path path) throws IOException {
    Path resolved = fileSystem.resolvePhysicalEntry(path);
    return resolved == null ? path : resolved;
  }

  private void discardBackups(Path backupDirectory, Throwable failure) {
    try {
      fileSystem.deleteTreeIfExists(backupDirectory);
    } catch (IOException | RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private Path commonParent(List<Path> paths) {
    if (paths.isEmpty()) {
      throw new IllegalArgumentException("Delegated outputs must not be empty");
    }
    Path common = parent(paths.getFirst());
    for (Path path : paths) {
      while (!path.startsWith(common)) {
        common = parent(common);
      }
    }
    return common;
  }

  private IOException accumulate(IOException current, Path path, Throwable failure) {
    IOException wrapped =
        new IOException(
            "Failed to restore delegated output: " + path + ": " + failure.getMessage(), failure);
    if (current == null) {
      return wrapped;
    }
    current.addSuppressed(wrapped);
    return current;
  }

  private Path parent(Path path) {
    Path parent = path.getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Delegated output has no parent: " + path);
    }
    return parent;
  }

  record Snapshot(List<Entry> entries, Path backupDirectory) {

    Snapshot {
      entries = List.copyOf(entries);
    }
  }

  private enum EntryKind {
    ABSENT,
    REGULAR,
    SYMLINK
  }

  private record Entry(
      Path path, EntryKind kind, Optional<Path> backup, Optional<Path> linkTarget) {

    private static Entry absent(Path path) {
      return new Entry(path, EntryKind.ABSENT, Optional.empty(), Optional.empty());
    }

    private static Entry regular(Path path, Path backup) {
      return new Entry(path, EntryKind.REGULAR, Optional.of(backup), Optional.empty());
    }

    private static Entry symlink(Path path, Path target) {
      return new Entry(path, EntryKind.SYMLINK, Optional.empty(), Optional.of(target));
    }
  }
}
