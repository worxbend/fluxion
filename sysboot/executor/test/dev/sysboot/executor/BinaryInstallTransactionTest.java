package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BinaryInstallTransactionTest {

  @TempDir Path tempDir;

  @Test
  void install_whenBinaryCommitFails_restoresPreviousSymlink() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path symlink = tempDir.resolve("rg-link");
    Files.createSymbolicLink(symlink, previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var commitFailed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path destination = invocation.getArgument(1, Path.class);
              if (destination.equals(installPath) && commitFailed.compareAndSet(false, true)) {
                throw new IOException("binary commit failure");
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, symlink),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("binary commit failure");
    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(previousTarget);
    assertThat(transactionArtifacts()).isEmpty();
  }

  @Test
  void install_whenBinaryDestinationIsDirectory_refusesWithoutDisplacingIt() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.createDirectory(tempDir.resolve("rg"));
    Path retained = Files.writeString(installPath.resolve("retained"), "keep");

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), new DefaultBinaryFileSystem())
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("non-file binary destination");

    assertThat(Files.readString(retained)).isEqualTo("keep");
    assertThat(transactionArtifacts()).isEmpty();
  }

  @Test
  void install_whenSymlinkDestinationIsDirectory_refusesWithoutDisplacingIt() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = tempDir.resolve("rg");
    Path symlink = Files.createDirectory(tempDir.resolve("rg-link"));
    Path retained = Files.writeString(symlink.resolve("retained"), "keep");

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), new DefaultBinaryFileSystem())
                    .install(
                        source,
                        module(installPath, symlink),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("non-symlink link destination");

    assertThat(Files.readString(retained)).isEqualTo("keep");
    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_commitsBinaryBeforeReplacingSymlink() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path symlink = tempDir.resolve("rg-link");
    Files.createSymbolicLink(symlink, previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doAnswer(
            invocation -> {
              Path destination = invocation.getArgument(1, Path.class);
              if (destination.equals(installPath)) {
                assertThat(Files.readSymbolicLink(symlink)).isEqualTo(previousTarget);
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
        .install(source, module(installPath, symlink), Optional.of(ArtifactDigests.sha256(source)));

    assertThat(Files.readString(installPath)).isEqualTo("new");
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(installPath);
  }

  @Test
  void install_whenReplacingRegularBinary_keepsDestinationPresentUntilAtomicMove()
      throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var replacementObserved = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path destination = invocation.getArgument(1, Path.class);
              if (destination.equals(installPath)) {
                assertThat(Files.readString(installPath)).isEqualTo("old");
                replacementObserved.set(true);
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
        .install(source, module(installPath, null), Optional.of(ArtifactDigests.sha256(source)));

    assertThat(replacementObserved).isTrue();
    verify(fileSystem)
        .createHardLink(any(Path.class), org.mockito.ArgumentMatchers.eq(installPath));
    assertThat(Files.readString(installPath)).isEqualTo("new");
  }

  @Test
  void install_whenBinaryMoveTakesEffectThenThrows_restoresPreviousBinary() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var failed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path destination = invocation.getArgument(1, Path.class);
              Object result = invocation.callRealMethod();
              if (destination.equals(installPath) && failed.compareAndSet(false, true)) {
                throw new IOException("post-effect binary failure");
              }
              return result;
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("post-effect binary failure");

    assertThat(Files.readString(installPath)).isEqualTo("old");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void install_whenEarlyRegularRecoveryInitiallyFails_reconcilesPriorState(
      boolean recoveryPostEffect) throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var publishFailed = new java.util.concurrent.atomic.AtomicBoolean();
    var recoveryFailed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path moveSource = invocation.getArgument(0, Path.class);
              Path destination = invocation.getArgument(1, Path.class);
              String sourceName = moveSource.getFileName().toString();
              if (destination.equals(installPath)
                  && sourceName.startsWith(".sysboot-binary-backup-")
                  && recoveryFailed.compareAndSet(false, true)) {
                if (recoveryPostEffect) {
                  invocation.callRealMethod();
                }
                throw new IOException("ambiguous early recovery failure");
              }
              Object result = invocation.callRealMethod();
              if (destination.equals(installPath)
                  && sourceName.startsWith(".sysboot-binary-")
                  && publishFailed.compareAndSet(false, true)) {
                throw new IOException("post-effect publication failure");
              }
              return result;
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("post-effect publication failure")
        .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());

    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(transactionArtifacts()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void install_whenEarlySymlinkBackupRecoveryInitiallyFails_reconcilesPriorState(
      boolean recoveryPostEffect) throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path installPath = Files.createSymbolicLink(tempDir.resolve("rg"), previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var backupFailed = new java.util.concurrent.atomic.AtomicBoolean();
    var recoveryFailed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path moveSource = invocation.getArgument(0, Path.class);
              Path destination = invocation.getArgument(1, Path.class);
              if (moveSource.equals(installPath) && backupFailed.compareAndSet(false, true)) {
                invocation.callRealMethod();
                throw new IOException("post-effect backup failure");
              }
              if (destination.equals(installPath)
                  && moveSource.getFileName().toString().startsWith(".sysboot-binary-backup-")
                  && recoveryFailed.compareAndSet(false, true)) {
                if (recoveryPostEffect) {
                  invocation.callRealMethod();
                }
                throw new IOException("ambiguous early recovery failure");
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("post-effect backup failure")
        .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());

    assertThat(Files.readSymbolicLink(installPath)).isEqualTo(previousTarget);
    assertThat(transactionArtifacts()).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void install_whenEarlyAbsentDestinationCleanupInitiallyFails_reconcilesAbsence(
      boolean cleanupPostEffect) throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = tempDir.resolve("rg");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var publishFailed = new java.util.concurrent.atomic.AtomicBoolean();
    var cleanupFailed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (invocation.getArgument(1, Path.class).equals(installPath)
                  && publishFailed.compareAndSet(false, true)) {
                throw new IOException("post-effect publication failure");
              }
              return result;
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));
    doAnswer(
            invocation -> {
              Path deleted = invocation.getArgument(0, Path.class);
              if (deleted.equals(installPath) && cleanupFailed.compareAndSet(false, true)) {
                if (cleanupPostEffect) {
                  invocation.callRealMethod();
                }
                throw new IOException("ambiguous early cleanup failure");
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .deleteIfExists(any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("post-effect publication failure")
        .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());

    assertThat(installPath).doesNotExist();
    assertThat(transactionArtifacts()).isEmpty();
  }

  @Test
  void install_whenSymlinkBackupMoveTakesEffectThenThrows_restoresBinaryDestination()
      throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path installPath = Files.createSymbolicLink(tempDir.resolve("rg"), previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doAnswer(
            invocation -> {
              Path moveSource = invocation.getArgument(0, Path.class);
              Object result = invocation.callRealMethod();
              if (moveSource.equals(installPath)) {
                throw new IOException("post-effect backup failure");
              }
              return result;
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("post-effect backup failure");

    assertThat(Files.readSymbolicLink(installPath)).isEqualTo(previousTarget);
    assertThat(transactionArtifacts()).isEmpty();
  }

  @Test
  void install_whenPublishingAbsentBinaryTakesEffectThenThrows_removesNewBinary() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = tempDir.resolve("rg");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              if (invocation.getArgument(1, Path.class).equals(installPath)) {
                throw new IOException("post-effect binary failure");
              }
              return result;
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class);

    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_whenSymlinkCommitFails_restoresPreviousBinaryAndSymlink() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path symlink = tempDir.resolve("rg-link");
    Files.createSymbolicLink(symlink, previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var commitFailed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path destination = invocation.getArgument(1, Path.class);
              if (destination.equals(symlink) && commitFailed.compareAndSet(false, true)) {
                throw new IOException("symlink commit failure");
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, symlink),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to commit binary symlink");

    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(previousTarget);
    try (var entries = Files.list(tempDir)) {
      assertThat(entries.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.startsWith(".sysboot-"));
    }
  }

  @Test
  void install_whenSymlinkBackupMoveTakesEffectThenThrows_restoresPriorState() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path symlink = tempDir.resolve("rg-link");
    Files.createSymbolicLink(symlink, previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var failed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path destination = invocation.getArgument(1, Path.class);
              Object result = invocation.callRealMethod();
              if (destination.getFileName().toString().startsWith(".sysboot-link-backup-")
                  && failed.compareAndSet(false, true)) {
                throw new IOException("post-effect symlink backup failure");
              }
              return result;
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, symlink),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to commit binary symlink");

    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(previousTarget);
  }

  @Test
  void install_whenFirstSymlinkRestoreAttemptFails_outerRollbackRetries() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path symlink = Files.createSymbolicLink(tempDir.resolve("rg-link"), previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var publishFailed = new java.util.concurrent.atomic.AtomicBoolean();
    var restoreFailed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path moveSource = invocation.getArgument(0, Path.class);
              Path destination = invocation.getArgument(1, Path.class);
              if (destination.equals(symlink)
                  && moveSource.getFileName().toString().startsWith(".sysboot-link-")
                  && publishFailed.compareAndSet(false, true)) {
                throw new IOException("symlink publication failure");
              }
              if (destination.equals(symlink)
                  && moveSource.getFileName().toString().startsWith(".sysboot-link-backup-")
                  && restoreFailed.compareAndSet(false, true)) {
                throw new IOException("transient restore failure");
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, symlink),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to commit binary symlink");

    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(previousTarget);
    assertThat(transactionArtifacts()).isEmpty();
  }

  @Test
  void install_whenPhysicalDestinationsAlias_refusesBeforeMutation() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path realDirectory = Files.createDirectory(tempDir.resolve("real"));
    Path aliasDirectory = Files.createSymbolicLink(tempDir.resolve("alias"), realDirectory);
    Path installPath = realDirectory.resolve("rg");
    Path symlink = aliasDirectory.resolve("rg");

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), new DefaultBinaryFileSystem())
                    .install(
                        source,
                        module(installPath, symlink),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("overlapping destinations");

    assertThat(installPath).doesNotExist();
    assertThat(transactionArtifacts()).isEmpty();
  }

  @Test
  void install_whenFirstBinaryRollbackMoveFails_retriesWithoutLosingPriorState() throws Exception {
    assertBinaryRollbackReconciles(false);
  }

  @Test
  void install_whenBinaryRollbackMoveTakesEffectThenThrows_doesNotReportIncompleteRollback()
      throws Exception {
    assertBinaryRollbackReconciles(true);
  }

  private void assertBinaryRollbackReconciles(boolean postEffect) throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path symlink = Files.createSymbolicLink(tempDir.resolve("rg-link"), previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var symlinkFailed = new java.util.concurrent.atomic.AtomicBoolean();
    var rollbackFailed = new java.util.concurrent.atomic.AtomicBoolean();
    doAnswer(
            invocation -> {
              Path moveSource = invocation.getArgument(0, Path.class);
              Path destination = invocation.getArgument(1, Path.class);
              String sourceName = moveSource.getFileName().toString();
              if (destination.equals(symlink)
                  && sourceName.startsWith(".sysboot-link-")
                  && !sourceName.startsWith(".sysboot-link-backup-")
                  && symlinkFailed.compareAndSet(false, true)) {
                throw new IOException("symlink publication failure");
              }
              if (destination.equals(installPath)
                  && sourceName.startsWith(".sysboot-binary-backup-")
                  && rollbackFailed.compareAndSet(false, true)) {
                if (postEffect) {
                  invocation.callRealMethod();
                }
                throw new IOException("ambiguous binary rollback failure");
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, symlink),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("Failed to commit binary symlink")
        .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());

    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(previousTarget);
    assertThat(transactionArtifacts()).isEmpty();
  }

  @Test
  void install_whenPrimaryAndStageCleanupFail_preservesPrimaryFailure() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = tempDir.resolve("rg");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doThrow(new IOException("copy failure"))
        .when(fileSystem)
        .copy(any(Path.class), any(Path.class));
    doThrow(new ShellExecutionException("cleanup failure"))
        .when(fileSystem)
        .deleteIfExists(any(Path.class));

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("copy failure")
        .satisfies(
            failure ->
                assertThat(failure.getSuppressed())
                    .anySatisfy(
                        suppressed ->
                            assertThat(suppressed.getMessage()).contains("cleanup failure")));
  }

  @Test
  void install_whenBackupCleanupThrowsRuntime_keepsCoherentCommittedState() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = Files.writeString(tempDir.resolve("rg"), "old");
    Path previousTarget = Files.writeString(tempDir.resolve("previous-rg"), "previous");
    Path symlink = tempDir.resolve("rg-link");
    Files.createSymbolicLink(symlink, previousTarget);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doAnswer(
            invocation -> {
              Path path = invocation.getArgument(0, Path.class);
              if (path.getFileName().toString().contains("backup")) {
                throw new ShellExecutionException("cleanup launch failure");
              }
              return invocation.callRealMethod();
            })
        .when(fileSystem)
        .deleteIfExists(any(Path.class));

    new BinaryInstallTransaction(mock(ShellRunner.class), fileSystem)
        .install(source, module(installPath, symlink), Optional.of(ArtifactDigests.sha256(source)));

    assertThat(Files.readString(installPath)).isEqualTo("new");
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(installPath);
  }

  @Test
  void install_whenPrivileged_usesVerifiedRootOwnedStageBeforeFinalMode() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = tempDir.resolve("rg");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    when(fileSystem.isWritable(tempDir)).thenReturn(false);
    when(fileSystem.isRootOwned(tempDir)).thenReturn(true);
    when(fileSystem.isSecurePrivilegedDirectory(tempDir)).thenReturn(true);
    var runner = new CapturingRunner();
    var publisher = new RecordingArtifactPublisher(tempDir.resolve(".root-stage"));

    new BinaryInstallTransaction(runner, fileSystem, publisher)
        .install(source, module(installPath, null), Optional.of(ArtifactDigests.sha256(source)));

    assertThat(publisher.mode).isEqualTo("0750");
    assertThat(publisher.expected).isEqualTo(ArtifactDigests.sha256(source));
    assertThat(runner.commands)
        .containsExactly(
            List.of(
                "sudo",
                TrustedSystemExecutable.move().toString(),
                "-fT",
                "--",
                tempDir.resolve(".root-stage").toString(),
                installPath.toString()));
  }

  @Test
  void install_whenPrivileged_usesCallerBoundDigestInsteadOfRebaseliningSource() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "trusted");
    Path installPath = tempDir.resolve("rg");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    when(fileSystem.isWritable(tempDir)).thenReturn(false);
    when(fileSystem.isRootOwned(tempDir)).thenReturn(true);
    when(fileSystem.isSecurePrivilegedDirectory(tempDir)).thenReturn(true);
    var runner = new CapturingRunner();
    Sha256Digest trustedDigest = ArtifactDigests.sha256(source);
    var publisher = new MutatingPublisher();

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(runner, fileSystem, publisher)
                    .install(source, module(installPath, null), Optional.of(trustedDigest)))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("publication failed");

    assertThat(publisher.expected).isEqualTo(trustedDigest);
    assertThat(runner.commands).isEmpty();
    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_whenPrivilegedPublisherReturnsDetailedFailure_preservesDiagnostics()
      throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "trusted");
    Path installPath = tempDir.resolve("rg");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    when(fileSystem.isWritable(tempDir)).thenReturn(false);
    when(fileSystem.isRootOwned(tempDir)).thenReturn(true);
    when(fileSystem.isSecurePrivilegedDirectory(tempDir)).thenReturn(true);

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(
                        mock(ShellRunner.class), fileSystem, new DetailedFailurePublisher())
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("artifact verification failed")
        .hasMessageContaining("root-owned artifact stage");

    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_whenPrivilegedParentIsNotSecure_refusesBeforeSudoStaging() throws Exception {
    Path source = Files.writeString(tempDir.resolve("source"), "new");
    Path installPath = tempDir.resolve("rg");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    when(fileSystem.isWritable(tempDir)).thenReturn(false);
    when(fileSystem.isRootOwned(tempDir)).thenReturn(true);
    when(fileSystem.isSecurePrivilegedDirectory(tempDir)).thenReturn(false);
    ShellRunner runner = mock(ShellRunner.class);

    assertThatThrownBy(
            () ->
                new BinaryInstallTransaction(runner, fileSystem)
                    .install(
                        source,
                        module(installPath, null),
                        Optional.of(ArtifactDigests.sha256(source))))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("unsafe or untrusted");
    verify(runner, never()).run(any(), any(), any());
    assertThat(installPath).doesNotExist();
  }

  private CompiledBinaryModule module(Path installPath, Path symlink) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(URI.create("https://example.test/rg")),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        installPath,
        Optional.empty(),
        0,
        Optional.of("0750"),
        Optional.ofNullable(symlink),
        false,
        Optional.empty(),
        Optional.empty());
  }

  private List<Path> transactionArtifacts() throws IOException {
    try (var paths = Files.list(tempDir)) {
      return paths.filter(path -> path.getFileName().toString().startsWith(".sysboot-")).toList();
    }
  }

  private static final class CapturingRunner implements ShellRunner {
    private final java.util.List<java.util.List<String>> commands = new java.util.ArrayList<>();

    @Override
    public dev.sysboot.core.ProcessResult run(
        java.util.List<String> command,
        java.util.Map<String, String> environment,
        java.time.Duration timeout) {
      commands.add(java.util.List.copyOf(command));
      return new dev.sysboot.core.ProcessResult(0, "", "", java.time.Duration.ZERO);
    }
  }

  private static final class RecordingArtifactPublisher implements PrivilegedArtifactPublisher {
    private final Path stage;
    private String mode;
    private dev.sysboot.core.Sha256Digest expected;

    private RecordingArtifactPublisher(Path stage) {
      this.stage = stage;
    }

    @Override
    public dev.sysboot.core.ProcessResult publish(
        Path source, Path destination, String mode, dev.sysboot.core.Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.sysboot.core.ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        dev.sysboot.core.Sha256Digest expected,
        StagedConsumer consumer)
        throws IOException {
      this.mode = mode;
      this.expected = expected;
      return consumer.consume(stage);
    }

    @Override
    public dev.sysboot.core.ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class MutatingPublisher implements PrivilegedArtifactPublisher {
    private Sha256Digest expected;

    @Override
    public dev.sysboot.core.ProcessResult publish(
        Path source, Path destination, String mode, Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.sysboot.core.ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        Sha256Digest expected,
        StagedConsumer consumer)
        throws IOException {
      this.expected = expected;
      Files.writeString(source, "attacker");
      boolean matches = ArtifactDigests.sha256(source).equals(expected);
      return new dev.sysboot.core.ProcessResult(matches ? 0 : 1, "", "", java.time.Duration.ZERO);
    }

    @Override
    public dev.sysboot.core.ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class DetailedFailurePublisher implements PrivilegedArtifactPublisher {

    @Override
    public dev.sysboot.core.ProcessResult publish(
        Path source, Path destination, String mode, Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public dev.sysboot.core.ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        Sha256Digest expected,
        StagedConsumer consumer) {
      return new dev.sysboot.core.ProcessResult(
          7,
          "",
          "artifact verification failed"
              + System.lineSeparator()
              + "Additionally failed to remove root-owned artifact stage",
          java.time.Duration.ZERO);
    }

    @Override
    public dev.sysboot.core.ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }
}
