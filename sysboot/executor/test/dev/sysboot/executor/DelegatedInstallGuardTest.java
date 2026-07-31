package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DelegatedInstallGuardTest {

  @TempDir Path tempDir;

  @Test
  void restore_reinstatesRegularSymlinkAndAbsentOutputsAfterPartialDelegate() throws Exception {
    Path regular = Files.writeString(tempDir.resolve("regular"), "old");
    Path target = Files.writeString(tempDir.resolve("target"), "target");
    Path symlink = Files.createSymbolicLink(tempDir.resolve("link"), target);
    Path absent = tempDir.resolve("absent");
    var guard = new DelegatedInstallGuard(new DefaultBinaryFileSystem());
    DelegatedInstallGuard.Snapshot snapshot = guard.prepare(List.of(regular, symlink, absent));

    Files.writeString(regular, "new");
    Files.delete(symlink);
    Files.createSymbolicLink(symlink, regular);
    Files.writeString(absent, "partial");

    guard.restore(snapshot);

    assertThat(Files.readString(regular)).isEqualTo("old");
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(target);
    assertThat(absent).doesNotExist();
    assertThat(backupPaths()).isEmpty();
  }

  @Test
  void commit_keepsDelegateOutputsAndRemovesBackups() throws Exception {
    Path regular = Files.writeString(tempDir.resolve("regular"), "old");
    var guard = new DelegatedInstallGuard(new DefaultBinaryFileSystem());
    DelegatedInstallGuard.Snapshot snapshot = guard.prepare(List.of(regular));

    Files.writeString(regular, "new");
    guard.commit(snapshot);

    assertThat(Files.readString(regular)).isEqualTo("new");
    assertThat(backupPaths()).isEmpty();
  }

  @Test
  void restore_removesUnexpectedNonemptyDirectoriesWithoutFollowingSymlinks() throws Exception {
    Path regular = Files.writeString(tempDir.resolve("regular"), "old");
    Path absent = tempDir.resolve("absent");
    Path outside = Files.writeString(tempDir.resolve("outside"), "outside");
    var guard = new DelegatedInstallGuard(new DefaultBinaryFileSystem());
    DelegatedInstallGuard.Snapshot snapshot = guard.prepare(List.of(regular, absent));

    Files.delete(regular);
    Files.createDirectories(regular.resolve("nested"));
    Files.createSymbolicLink(regular.resolve("nested/link"), outside);
    Files.createDirectories(absent.resolve("partial"));
    Files.writeString(absent.resolve("partial/file"), "partial");

    guard.restore(snapshot);

    assertThat(Files.readString(regular)).isEqualTo("old");
    assertThat(absent).doesNotExist();
    assertThat(Files.readString(outside)).isEqualTo("outside");
    assertThat(backupPaths()).isEmpty();
  }

  @Test
  void restore_whenDelegateRemovesManagedParents_recreatesHierarchyFromExternalBackup()
      throws Exception {
    Path regular = tempDir.resolve("managed/tool/bin/rg");
    Files.createDirectories(regular.getParent());
    Files.writeString(regular, "old");
    Path declared = tempDir.resolve("bin/rg");
    Files.createDirectories(declared.getParent());
    Files.createSymbolicLink(declared, regular);
    var guard = new DelegatedInstallGuard(new DefaultBinaryFileSystem());
    DelegatedInstallGuard.Snapshot snapshot = guard.prepare(List.of(regular, declared));

    new DefaultBinaryFileSystem().deleteTreeIfExists(tempDir.resolve("managed"));
    new DefaultBinaryFileSystem().deleteTreeIfExists(tempDir.resolve("bin"));

    guard.restore(snapshot);

    assertThat(Files.readString(regular)).isEqualTo("old");
    assertThat(Files.readSymbolicLink(declared)).isEqualTo(regular);
    assertThat(backupPaths()).isEmpty();
  }

  @Test
  void prepare_whenOutputsUsePhysicalAlias_refusesBeforeCreatingBackups() throws Exception {
    Path realDirectory = Files.createDirectory(tempDir.resolve("real"));
    Path aliasDirectory = Files.createSymbolicLink(tempDir.resolve("alias"), realDirectory);
    var guard = new DelegatedInstallGuard(new DefaultBinaryFileSystem());

    assertThatThrownBy(
            () -> guard.prepare(List.of(realDirectory.resolve("rg"), aliasDirectory.resolve("rg"))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("overlapping delegated outputs");

    assertThat(backupPaths()).isEmpty();
  }

  @Test
  void restore_whenFirstRegularRestoreFails_retainsBackupForRetry() throws Exception {
    Path regular = Files.writeString(tempDir.resolve("regular"), "old");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var failed = new AtomicBoolean();
    doAnswer(
            invocation -> {
              Path source = invocation.getArgument(0, Path.class);
              Path destination = invocation.getArgument(1, Path.class);
              if (destination.equals(regular)
                  && source.getFileName().toString().startsWith(".sysboot-delegate-restore-")
                  && failed.compareAndSet(false, true)) {
                throw new IOException("transient restore failure");
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));
    var guard = new DelegatedInstallGuard(fileSystem);
    DelegatedInstallGuard.Snapshot snapshot = guard.prepare(List.of(regular));
    Files.writeString(regular, "new");

    assertThatThrownBy(() -> guard.restore(snapshot))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to restore delegated output");
    assertThat(backupPaths()).isNotEmpty();

    guard.restore(snapshot);

    assertThat(Files.readString(regular)).isEqualTo("old");
    assertThat(backupPaths()).isEmpty();
  }

  @Test
  void restore_whenDelegateIntroducesAncestorSymlink_refusesWithoutTouchingExternalTree()
      throws Exception {
    Path managed = tempDir.resolve("managed");
    Path regular = managed.resolve("tool/rg");
    Files.createDirectories(regular.getParent());
    Files.writeString(regular, "old");
    Path declared = tempDir.resolve("bin/rg");
    Files.createDirectories(declared.getParent());
    Files.createSymbolicLink(declared, regular);
    Path external = Files.createDirectory(tempDir.resolve("external"));
    Path externalTool = Files.createDirectory(external.resolve("tool"));
    Path sentinel = Files.writeString(externalTool.resolve("rg"), "external");
    var guard = new DelegatedInstallGuard(new DefaultBinaryFileSystem());
    DelegatedInstallGuard.Snapshot snapshot = guard.prepare(List.of(regular, declared));

    new DefaultBinaryFileSystem().deleteTreeIfExists(managed);
    Files.createSymbolicLink(managed, external);

    assertThatThrownBy(() -> guard.restore(snapshot))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("symlinked or non-directory path ancestor");
    assertThat(Files.readString(sentinel)).isEqualTo("external");
    assertThat(backupPaths()).isNotEmpty();

    Files.delete(managed);
    guard.restore(snapshot);

    assertThat(Files.readString(regular)).isEqualTo("old");
    assertThat(Files.readSymbolicLink(declared)).isEqualTo(regular);
    assertThat(Files.readString(sentinel)).isEqualTo("external");
    assertThat(backupPaths()).isEmpty();
  }

  private List<Path> backupPaths() throws Exception {
    try (var paths = Files.list(tempDir)) {
      return paths
          .filter(path -> path.getFileName().toString().startsWith(".sysboot-delegate-backup"))
          .toList();
    }
  }
}
