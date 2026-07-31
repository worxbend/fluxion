package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.StateEntry;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonStateRepositoryTest {

  @TempDir Path tempDir;

  private JsonStateRepository newRepo() {
    return testRepo(tempDir);
  }

  @Test
  void load_whenNoFile_returnsEmpty() {
    Optional<BootstrapState> result = newRepo().load("test-profile");
    assertThat(result).isEmpty();
  }

  @Test
  void path_returnsFluxionStatePath() {
    Path stateFile = newRepo().path("test-profile");
    assertThat(stateFile).isEqualTo(tempDir.resolve("test-profile.state.json"));
  }

  @Test
  void path_whenProfileIsUnsafe_rejectsBeforeResolvingStatePath() {
    List<String> unsafeProfiles =
        List.of(
            "../outside",
            "nested/profile",
            "nested\\profile",
            "/tmp/absolute",
            ".",
            "..",
            "profile..backup",
            "profile\nname");

    assertThat(unsafeProfiles)
        .allSatisfy(
            profile ->
                assertThatThrownBy(() -> newRepo().path(profile))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("safe slug"));
  }

  @Test
  void path_whenProfileIsOrdinary_normalizesUnderStateRoot() {
    Path stateFile = newRepo().path("fedora-41_dev.v2");

    assertThat(stateFile).isEqualTo(tempDir.resolve("fedora-41_dev.v2.state.json"));
    assertThat(stateFile.normalize().startsWith(tempDir.toAbsolutePath().normalize())).isTrue();
  }

  @Test
  void path_whenProfileHasRepeatedSeparators_acceptsCompatibleSlug() {
    assertThat(newRepo().path("team--dev")).isEqualTo(tempDir.resolve("team--dev.state.json"));
    assertThat(newRepo().path("release__candidate"))
        .isEqualTo(tempDir.resolve("release__candidate.state.json"));
  }

  @Test
  void load_whenLegacyFileExists_readsLegacyState() throws Exception {
    Path currentDir = tempDir.resolve("current");
    Path legacyDir = tempDir.resolve("legacy");
    var repo = new JsonStateRepository(new StatePaths(currentDir, legacyDir), new ObjectMapper());
    Files.createDirectories(legacyDir);
    Files.writeString(
        legacyDir.resolve("test-profile.state.json"),
        """
        {
          "schemaVersion": 2,
          "profileName": "test-profile",
          "entries": [],
          "phaseEntries": []
        }
        """);

    Optional<BootstrapState> result = repo.load("test-profile");

    assertThat(result).isPresent();
    assertThat(result.get().profileName()).isEqualTo("test-profile");
    assertThat(result.get().lastRunAt()).isEqualTo(Instant.EPOCH);
    assertThat(result.get().sysbootVersion()).isEqualTo("legacy-state-v2");
  }

  @Test
  void load_whenStateFileIsCorrupt_throwsStateReadException() throws Exception {
    Files.createDirectories(tempDir);
    Files.writeString(tempDir.resolve("test-profile.state.json"), "{ not-json");

    assertThatThrownBy(() -> newRepo().load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasMessageContaining("test-profile.state.json");
  }

  @Test
  void saveAndLoad_roundTrip_preservesEntries() {
    StateEntry entry =
        new StateEntry(
            "test-profile",
            "core-tools",
            "git",
            ItemType.PACKAGE,
            Instant.parse("2026-06-01T10:00:00Z"),
            Optional.of("2.45.1"),
            Optional.empty());
    BootstrapState state = BootstrapState.empty("test-profile", "1.0.0").withEntry(entry);

    var repo = newRepo();
    repo.save(state);

    Optional<BootstrapState> loaded = repo.load("test-profile");
    assertThat(loaded).isPresent();
    assertThat(loaded.get().profileName()).isEqualTo("test-profile");
    assertThat(loaded.get().entries()).hasSize(1);
    assertThat(loaded.get().entries().get(0).itemKey()).isEqualTo("git");
    assertThat(loaded.get().entries().get(0).itemType()).isEqualTo(ItemType.PACKAGE);
    assertThat(loaded.get().entries().get(0).version()).contains("2.45.1");
  }

  @Test
  void saveAndLoad_roundTrip_preservesLastRunTimestampAndVersion() throws Exception {
    Instant lastRun = Instant.parse("2026-07-31T03:14:15Z");
    BootstrapState state = BootstrapState.empty("test-profile", "2.7.4", lastRun);

    var repo = newRepo();
    repo.save(state);

    BootstrapState loaded = repo.load("test-profile").orElseThrow();
    assertThat(loaded.lastRunAt()).isEqualTo(lastRun);
    assertThat(loaded.sysbootVersion()).isEqualTo("2.7.4");
    assertThat(Files.readString(repo.path("test-profile")))
        .contains("\"schemaVersion\" : 7")
        .contains("\"lastRunAt\" : \"2026-07-31T03:14:15Z\"")
        .contains("\"sysbootVersion\" : \"2.7.4\"");
  }

  @Test
  void load_whenSchemaVersionIsUnsupported_failsClosed() throws Exception {
    writeState(
        "test-profile",
        """
        {
          "schemaVersion": 99,
          "profileName": "test-profile",
          "entries": []
        }
        """);

    assertThatThrownBy(() -> newRepo().load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("Unsupported state schemaVersion: 99");
  }

  @Test
  void load_whenOuterProfileDiffersFromRequested_failsClosed() throws Exception {
    writeState(
        "test-profile",
        """
        {
          "schemaVersion": 2,
          "profileName": "other-profile",
          "entries": []
        }
        """);

    assertThatThrownBy(() -> newRepo().load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("State profile does not match requested profile: test-profile");
  }

  @Test
  void load_whenEntryProfileDiffersFromRequested_failsClosed() throws Exception {
    writeState(
        "test-profile",
        """
        {
          "schemaVersion": 2,
          "profileName": "test-profile",
          "entries": [{
            "profileName": "other-profile",
            "moduleName": "tools",
            "itemKey": "git",
            "itemType": "PACKAGE",
            "completedAt": "2026-07-31T03:14:15Z"
          }]
        }
        """);

    assertThatThrownBy(() -> newRepo().load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("State entry profile does not match requested profile: test-profile");
  }

  @Test
  void save_whenPredictableTempSymlinkExists_doesNotFollowIt() throws Exception {
    Path stateRoot = tempDir.resolve("state");
    Path outside = tempDir.resolve("outside");
    Path predictableTemp = stateRoot.resolve("test-profile.state.json.tmp");
    Files.createDirectories(stateRoot);
    Files.writeString(outside, "outside");
    createSymlinkOrSkip(predictableTemp, outside);

    var repo = testRepo(stateRoot);
    repo.save(BootstrapState.empty("test-profile", "1.0.0"));

    assertThat(outside).hasContent("outside");
    assertThat(predictableTemp).isSymbolicLink();
    assertThat(repo.load("test-profile")).isPresent();
  }

  @Test
  void load_whenStateFileSymlinksOutsideRoot_rejectsEscape() throws Exception {
    Path stateRoot = tempDir.resolve("state");
    Path outside = tempDir.resolve("outside-state.json");
    Files.createDirectories(stateRoot);
    Files.writeString(
        outside,
        """
        {
          "schemaVersion": 2,
          "profileName": "test-profile",
          "entries": [],
          "phaseEntries": []
        }
        """);
    createSymlinkOrSkip(stateRoot.resolve("test-profile.state.json"), outside);
    var repo = testRepo(stateRoot);

    assertThatThrownBy(() -> repo.load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasMessageContaining("test-profile");
  }

  @Test
  void save_onPosix_createsPrivateDirectoryAndStateFile() throws Exception {
    assumePosix();
    Path stateRoot = tempDir.resolve("private-state");
    var repo = testRepo(stateRoot);

    repo.save(BootstrapState.empty("test-profile", "1.0.0"));

    assertThat(Files.getPosixFilePermissions(stateRoot))
        .isEqualTo(PosixFilePermissions.fromString("rwx------"));
    assertThat(Files.getPosixFilePermissions(repo.path("test-profile")))
        .isEqualTo(PosixFilePermissions.fromString("rw-------"));
  }

  @Test
  void load_onPosix_rejectsPreviouslyWritableDirectoryAndStateFile() throws Exception {
    assumePosix();
    Path stateFile = tempDir.resolve("test-profile.state.json");
    Files.writeString(
        stateFile,
        """
        {
          "schemaVersion": 2,
          "profileName": "test-profile",
          "entries": [],
          "phaseEntries": []
        }
        """);
    Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwxrwxrwx"));
    Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-rw-rw-"));

    assertThatThrownBy(() -> newRepo().load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("State path was writable by another account: " + tempDir);
    assertThat(Files.getPosixFilePermissions(tempDir))
        .isEqualTo(PosixFilePermissions.fromString("rwxrwxrwx"));
    assertThat(Files.getPosixFilePermissions(stateFile))
        .isEqualTo(PosixFilePermissions.fromString("rw-rw-rw-"));
  }

  @Test
  void load_onPosix_rejectsPreviouslyWritableStateFileInPrivateDirectory() throws Exception {
    assumePosix();
    Path stateRoot = tempDir.resolve("private-root");
    Files.createDirectory(
        stateRoot,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    Path stateFile = stateRoot.resolve("test-profile.state.json");
    writeEmptyState(stateFile);
    Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-rw-rw-"));

    assertThatThrownBy(() -> testRepo(stateRoot).load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("State file was writable by another account: " + stateFile);
  }

  @Test
  void loadReadOnly_onPrivateState_preservesPermissionsMtimeAndDirectoryLayout() throws Exception {
    assumePosix();
    Path stateRoot = tempDir.resolve("read-only-state");
    var repo = testRepo(stateRoot);
    repo.save(BootstrapState.empty("test-profile", "1.0.0"));
    Path stateFile = repo.path("test-profile");
    var timestamp = java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));
    Files.setLastModifiedTime(stateRoot, timestamp);
    Files.setLastModifiedTime(stateFile, timestamp);
    var directoryPermissions = Files.getPosixFilePermissions(stateRoot);
    var filePermissions = Files.getPosixFilePermissions(stateFile);
    List<String> layout = directoryLayout(stateRoot);

    assertThat(repo.loadReadOnly("test-profile")).isPresent();

    assertThat(Files.getPosixFilePermissions(stateRoot)).isEqualTo(directoryPermissions);
    assertThat(Files.getPosixFilePermissions(stateFile)).isEqualTo(filePermissions);
    assertThat(Files.getLastModifiedTime(stateRoot)).isEqualTo(timestamp);
    assertThat(Files.getLastModifiedTime(stateFile)).isEqualTo(timestamp);
    assertThat(directoryLayout(stateRoot)).isEqualTo(layout);
  }

  @Test
  void loadReadOnly_whenStateRootsAreAbsent_doesNotCreateEitherLayout() {
    Path currentRoot = tempDir.resolve("missing-current");
    Path legacyRoot = tempDir.resolve("missing-legacy");
    var repo = new JsonStateRepository(new StatePaths(currentRoot, legacyRoot), new ObjectMapper());

    assertThat(repo.loadReadOnly("test-profile")).isEmpty();

    assertThat(currentRoot).doesNotExist();
    assertThat(legacyRoot).doesNotExist();
  }

  @Test
  void loadReadOnly_onBroadPermissions_refusesStateWithoutRepairOrLayoutChanges() throws Exception {
    assumePosix();
    Path stateRoot = tempDir.resolve("broad-read-only-state");
    Path stateFile = stateRoot.resolve("test-profile.state.json");
    Files.createDirectories(stateRoot);
    writeEmptyState(stateFile);
    Files.setPosixFilePermissions(stateRoot, PosixFilePermissions.fromString("rwxrwxrwx"));
    Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-rw-rw-"));
    var timestamp = java.nio.file.attribute.FileTime.from(Instant.parse("2026-01-02T03:04:05Z"));
    Files.setLastModifiedTime(stateRoot, timestamp);
    Files.setLastModifiedTime(stateFile, timestamp);
    List<String> layout = directoryLayout(stateRoot);

    assertThatThrownBy(() -> testRepo(stateRoot).loadReadOnly("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("State path was writable by another account: " + stateRoot);

    assertThat(Files.getPosixFilePermissions(stateRoot))
        .isEqualTo(PosixFilePermissions.fromString("rwxrwxrwx"));
    assertThat(Files.getPosixFilePermissions(stateFile))
        .isEqualTo(PosixFilePermissions.fromString("rw-rw-rw-"));
    assertThat(Files.getLastModifiedTime(stateRoot)).isEqualTo(timestamp);
    assertThat(Files.getLastModifiedTime(stateFile)).isEqualTo(timestamp);
    assertThat(directoryLayout(stateRoot)).isEqualTo(layout);
  }

  @Test
  void load_whenPermissionRepairIsDenied_failsClosed() throws Exception {
    assumePosix();
    Path stateRoot = tempDir.resolve("state");
    Path stateFile = stateRoot.resolve("test-profile.state.json");
    Files.createDirectories(stateRoot);
    writeEmptyState(stateFile);
    var repo = repoWithDeniedPermissions(stateRoot);

    assertThatThrownBy(() -> repo.load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasCauseInstanceOf(AccessDeniedException.class);
  }

  @Test
  void reset_whenPermissionRepairIsDenied_deletesExistingState() throws Exception {
    Path stateRoot = tempDir.resolve("state");
    Path stateFile = stateRoot.resolve("test-profile.state.json");
    Files.createDirectories(stateRoot);
    Files.writeString(stateFile, "{}");
    var repo = repoWithDeniedPermissions(stateRoot);

    repo.reset("test-profile");

    assertThat(stateFile).doesNotExist();
  }

  @Test
  void save_whenPrivatePermissionEnforcementIsDenied_failsClosed() {
    Path stateRoot = tempDir.resolve("state");
    var repo = repoWithDeniedPermissions(stateRoot);

    assertThatThrownBy(() -> repo.save(BootstrapState.empty("test-profile", "1.0.0")))
        .isInstanceOf(StateWriteException.class);
    assertThat(repo.path("test-profile")).doesNotExist();
  }

  @Test
  void save_whenTempPermissionEnforcementFails_preservesCommittedState() throws Exception {
    Path stateRoot = tempDir.resolve("state");
    Path stateFile = stateRoot.resolve("test-profile.state.json");
    Files.createDirectories(stateRoot);
    Files.writeString(stateFile, "committed-state");
    var repo =
        new JsonStateRepository(
            testStatePaths(stateRoot),
            new ObjectMapper(),
            (path, permissions) -> {
              if (!path.equals(stateRoot.toAbsolutePath())) {
                throw new AccessDeniedException(path.toString());
              }
            });

    assertThatThrownBy(() -> repo.save(BootstrapState.empty("test-profile", "1.0.0")))
        .isInstanceOf(StateWriteException.class);

    assertThat(stateFile).hasContent("committed-state");
    try (var files = Files.list(stateRoot)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .noneMatch(name -> name.endsWith(".tmp"));
    }
  }

  @Test
  void load_whenPermissionRepairThrowsRuntimeFailure_failsClosed() throws Exception {
    assumePosix();
    Path stateRoot = tempDir.resolve("state");
    Files.createDirectories(stateRoot);
    writeEmptyState(stateRoot.resolve("test-profile.state.json"));

    for (RuntimeException failure :
        List.of(
            new SecurityException("denied"), new UnsupportedOperationException("unsupported"))) {
      assertThatThrownBy(() -> repoWithPermissionFailure(stateRoot, failure).load("test-profile"))
          .isInstanceOf(StateReadException.class)
          .hasCause(failure);
    }
  }

  @Test
  void reset_whenPermissionRepairThrowsRuntimeFailure_deletesExistingState() throws Exception {
    Path stateRoot = tempDir.resolve("state");
    Files.createDirectories(stateRoot);

    for (RuntimeException failure :
        List.of(
            new SecurityException("denied"), new UnsupportedOperationException("unsupported"))) {
      Path stateFile = stateRoot.resolve("test-profile.state.json");
      Files.writeString(stateFile, "{}");
      repoWithPermissionFailure(stateRoot, failure).reset("test-profile");
      assertThat(stateFile).doesNotExist();
    }
  }

  @Test
  void save_whenPermissionEnforcementThrowsRuntimeFailure_translatesFailure() {
    List<RuntimeException> failures =
        List.of(new SecurityException("denied"), new UnsupportedOperationException("unsupported"));

    for (int index = 0; index < failures.size(); index++) {
      Path stateRoot = tempDir.resolve("state-" + index);
      var repo = repoWithPermissionFailure(stateRoot, failures.get(index));

      assertThatThrownBy(() -> repo.save(BootstrapState.empty("test-profile", "1.0.0")))
          .isInstanceOf(StateWriteException.class)
          .hasCause(failures.get(index));
      assertThat(repo.path("test-profile")).doesNotExist();
    }
  }

  @Test
  void load_whenStatePathIsDirectory_failsWithoutParsing() throws Exception {
    Path stateFile = tempDir.resolve("test-profile.state.json");
    Files.createDirectory(stateFile);

    assertThatThrownBy(() -> newRepo().load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("State file must be a regular file: " + stateFile);
  }

  @Test
  void load_whenStatePathIsFifo_failsWithoutOpeningIt() throws Exception {
    Path stateFile = tempDir.resolve("test-profile.state.json");
    Process fifo = new ProcessBuilder("mkfifo", stateFile.toString()).start();
    Assumptions.assumeTrue(fifo.waitFor() == 0, "mkfifo unavailable");

    org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        java.time.Duration.ofSeconds(2),
        () ->
            assertThatThrownBy(() -> newRepo().load("test-profile"))
                .isInstanceOf(StateReadException.class)
                .hasRootCauseMessage("State file must be a regular file: " + stateFile));
  }

  @Test
  void load_whenOwnerVerificationFails_failsClosed() throws Exception {
    Path stateRoot = tempDir.resolve("owner-state");
    Files.createDirectories(stateRoot);
    writeEmptyState(stateRoot.resolve("test-profile.state.json"));
    var repo =
        new JsonStateRepository(
            testStatePaths(stateRoot),
            new ObjectMapper(),
            this::setPermissions,
            path -> {
              throw new IOException("wrong owner");
            });

    assertThatThrownBy(() -> repo.load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("wrong owner");
  }

  @Test
  void load_whenStateFileChmodRepairIsDenied_failsClosed() throws Exception {
    assumePosix();
    Path stateRoot = tempDir.resolve("chmod-state");
    Path stateFile = stateRoot.resolve("test-profile.state.json");
    Files.createDirectories(stateRoot);
    writeEmptyState(stateFile);
    Files.setPosixFilePermissions(stateRoot, PosixFilePermissions.fromString("rwx------"));
    Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-r--r--"));
    var repo =
        new JsonStateRepository(
            testStatePaths(stateRoot),
            new ObjectMapper(),
            (path, permissions) -> {
              if (path.equals(stateFile)) {
                throw new AccessDeniedException(path.toString());
              }
              Files.setPosixFilePermissions(path, permissions);
            });

    assertThatThrownBy(() -> repo.load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasCauseInstanceOf(AccessDeniedException.class);
  }

  @Test
  void load_whenChmodClaimsSuccessButLeavesBroadPermissions_failsClosed() throws Exception {
    assumePosix();
    Path stateRoot = tempDir.resolve("unchanged-mode-state");
    Path stateFile = stateRoot.resolve("test-profile.state.json");
    Files.createDirectories(stateRoot);
    writeEmptyState(stateFile);
    Files.setPosixFilePermissions(stateRoot, PosixFilePermissions.fromString("rwx------"));
    Files.setPosixFilePermissions(stateFile, PosixFilePermissions.fromString("rw-r--r--"));
    var repo =
        new JsonStateRepository(
            testStatePaths(stateRoot), new ObjectMapper(), (path, permissions) -> {});

    assertThatThrownBy(() -> repo.load("test-profile"))
        .isInstanceOf(StateReadException.class)
        .hasRootCauseMessage("State file permissions are not private: " + stateFile);
  }

  @Test
  void save_whenEntryProfileDiffers_refusesUnanchoredState() {
    StateEntry wrong =
        new StateEntry(
            "other-profile",
            "tools",
            "git",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty());
    BootstrapState state = BootstrapState.empty("test-profile", "1.0.0").withEntry(wrong);
    var repo = newRepo();

    assertThatThrownBy(() -> repo.save(state))
        .isInstanceOf(StateWriteException.class)
        .hasMessageContaining("entry profile");
    assertThat(repo.path("test-profile")).doesNotExist();
  }

  @Test
  void update_whenTransitionChangesRequestedProfile_refusesUnanchoredState() {
    var repo = newRepo();

    assertThatThrownBy(
            () ->
                repo.update(
                    "test-profile", ignored -> BootstrapState.empty("other-profile", "1.0.0")))
        .isInstanceOf(StateWriteException.class)
        .hasMessageContaining("requested profile");
    assertThat(repo.path("test-profile")).doesNotExist();
  }

  @Test
  void saveAndLoad_roundTrip_preservesBinaryProvenance() {
    StateEntry entry =
        new StateEntry(
            "test-profile",
            "ripgrep",
            "/usr/local/bin/rg",
            ItemType.COMPILED_BINARY,
            Instant.parse("2026-06-01T10:00:00Z"),
            Optional.of("14.1.1"),
            Optional.of("a".repeat(64)),
            Optional.of("https://example.test/rg.tar.gz"));
    BootstrapState state = BootstrapState.empty("test-profile", "1.0.0").withEntry(entry);

    var repo = newRepo();
    repo.save(state);

    Optional<BootstrapState> loaded = repo.load("test-profile");
    assertThat(loaded).isPresent();
    StateEntry loadedEntry = loaded.get().entries().getFirst();
    assertThat(loadedEntry.version()).contains("14.1.1");
    assertThat(loadedEntry.checksum()).contains("a".repeat(64));
    assertThat(loadedEntry.sourceUrl()).contains("https://example.test/rg.tar.gz");
  }

  @Test
  void saveAndLoad_roundTrip_preservesPhaseFingerprint() {
    var phaseEntry =
        new PhaseStateEntry(
            "foundation",
            PhaseStatus.COMPLETED,
            Instant.parse("2026-06-01T10:00:00Z"),
            Optional.of("abc123"));
    BootstrapState state = BootstrapState.empty("test-profile", "1.0.0").withPhaseEntry(phaseEntry);

    var repo = newRepo();
    repo.save(state);

    Optional<BootstrapState> loaded = repo.load("test-profile");
    assertThat(loaded).isPresent();
    assertThat(loaded.get().phaseEntries()).hasSize(1);
    assertThat(loaded.get().phaseEntries().get(0).fingerprint()).contains("abc123");
  }

  @Test
  void saveAndLoad_roundTrip_preservesPhaseReason() {
    var phaseEntry =
        new PhaseStateEntry(
            "foundation",
            PhaseStatus.FAILED,
            Instant.parse("2026-06-01T10:00:00Z"),
            Optional.of("abc123"),
            Optional.of("Phase stopped after a module failure"));
    BootstrapState state = BootstrapState.empty("test-profile", "1.0.0").withPhaseEntry(phaseEntry);

    var repo = newRepo();
    repo.save(state);

    Optional<BootstrapState> loaded = repo.load("test-profile");
    assertThat(loaded).isPresent();
    assertThat(loaded.get().phaseEntries()).hasSize(1);
    assertThat(loaded.get().phaseEntries().get(0).reason())
        .contains("Phase stopped after a module failure");
  }

  @Test
  void recordSuccess_addsEntryToExistingState() {
    var repo = newRepo();
    StateEntry first =
        new StateEntry(
            "p", "m", "curl", ItemType.PACKAGE, Instant.now(), Optional.empty(), Optional.empty());
    BootstrapState firstState = repo.recordSuccess("p", first);

    StateEntry second =
        new StateEntry(
            "p", "m", "wget", ItemType.PACKAGE, Instant.now(), Optional.empty(), Optional.empty());
    BootstrapState secondState = repo.recordSuccess("p", second);

    Optional<BootstrapState> state = repo.load("p");
    assertThat(state).isPresent();
    assertThat(state.get().entries()).hasSize(2);
    assertThat(firstState.entries()).hasSize(1);
    assertThat(secondState.entries()).hasSize(2);
  }

  @Test
  void recordSuccess_concurrentRepositories_doNotLoseUpdates() throws Exception {
    Path stateRoot = tempDir.resolve("concurrent-state");
    var firstRepo = testRepo(stateRoot);
    var secondRepo = testRepo(stateRoot);
    firstRepo.save(BootstrapState.empty("p", "1.0.0"));
    int itemCount = 40;
    List<Callable<BootstrapState>> writes =
        java.util.stream.IntStream.range(0, itemCount)
            .mapToObj(
                index ->
                    (Callable<BootstrapState>)
                        () ->
                            (index % 2 == 0 ? firstRepo : secondRepo)
                                .recordSuccess(
                                    "p",
                                    new StateEntry(
                                        "p",
                                        "module-" + index,
                                        "item-" + index,
                                        ItemType.PACKAGE,
                                        Instant.now(),
                                        Optional.empty(),
                                        Optional.empty())))
            .toList();

    try (var executor = Executors.newFixedThreadPool(8)) {
      for (var future : executor.invokeAll(writes)) {
        future.get();
      }
    }

    assertThat(firstRepo.load("p").orElseThrow().entries())
        .hasSize(itemCount)
        .extracting(StateEntry::itemKey)
        .contains("item-0", "item-39");
  }

  @Test
  void save_whenLockFileIsSymlink_rejectsWithoutChangingTarget() throws Exception {
    Path stateRoot = tempDir.resolve("symlink-lock-state");
    Path outside = tempDir.resolve("outside-lock-target");
    Files.createDirectories(stateRoot);
    Files.writeString(outside, "outside");
    createSymlinkOrSkip(stateRoot.resolve(".p.state.json.lock"), outside);

    assertThatThrownBy(() -> testRepo(stateRoot).save(BootstrapState.empty("p", "1.0.0")))
        .isInstanceOf(StateWriteException.class)
        .hasMessageContaining("lock state");
    assertThat(outside).hasContent("outside");
    assertThat(stateRoot.resolve("p.state.json")).doesNotExist();
  }

  @Test
  void recordSuccess_upsertsByItemKey() {
    var repo = newRepo();
    StateEntry v1 =
        new StateEntry(
            "p",
            "m",
            "git",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.of("2.40.0"),
            Optional.empty());
    repo.recordSuccess("p", v1);

    StateEntry v2 =
        new StateEntry(
            "p",
            "m",
            "git",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.of("2.45.1"),
            Optional.empty());
    repo.recordSuccess("p", v2);

    Optional<BootstrapState> state = repo.load("p");
    assertThat(state.get().entries()).hasSize(1);
    assertThat(state.get().entries().get(0).version()).contains("2.45.1");
  }

  @Test
  void save_multipleProfiles_areIsolated() {
    var repo = newRepo();
    StateEntry e1 =
        new StateEntry(
            "alpha",
            "m",
            "git",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty());
    StateEntry e2 =
        new StateEntry(
            "beta",
            "m",
            "curl",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty());
    repo.recordSuccess("alpha", e1);
    repo.recordSuccess("beta", e2);

    assertThat(repo.load("alpha").get().entries()).hasSize(1);
    assertThat(repo.load("beta").get().entries()).hasSize(1);
    assertThat(repo.load("alpha").get().entries().get(0).itemKey()).isEqualTo("git");
  }

  @Test
  void reset_deletesCurrentAndLegacyStateFiles() throws Exception {
    Path currentDir = tempDir.resolve("current");
    Path legacyDir = tempDir.resolve("legacy");
    var repo = new JsonStateRepository(new StatePaths(currentDir, legacyDir), new ObjectMapper());
    Files.createDirectories(currentDir);
    Files.createDirectories(legacyDir);
    Files.writeString(currentDir.resolve("p.state.json"), "{}");
    Files.writeString(legacyDir.resolve("p.state.json"), "{}");

    repo.reset("p");

    assertThat(currentDir.resolve("p.state.json")).doesNotExist();
    assertThat(legacyDir.resolve("p.state.json")).doesNotExist();
  }

  @Test
  void forgetItem_removesOnlyMatchingItem() {
    var repo = newRepo();
    repo.recordSuccess(
        "p",
        new StateEntry(
            "p", "m", "git", ItemType.PACKAGE, Instant.now(), Optional.empty(), Optional.empty()));
    repo.recordSuccess(
        "p",
        new StateEntry(
            "p", "m", "curl", ItemType.PACKAGE, Instant.now(), Optional.empty(), Optional.empty()));

    Optional<BootstrapState> updated = repo.forgetItem("p", "git");

    assertThat(updated).isPresent();
    assertThat(updated.get().entries()).extracting(StateEntry::itemKey).containsExactly("curl");
    assertThat(repo.load("p").orElseThrow().entries())
        .extracting(StateEntry::itemKey)
        .containsExactly("curl");
  }

  @Test
  void forgetItem_withCanonicalIdentity_keepsSameKeyFromOtherModule() {
    var repo = newRepo();
    repo.recordSuccess(
        "p",
        new StateEntry(
            "p",
            "core",
            "shared",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty()));
    repo.recordSuccess(
        "p",
        new StateEntry(
            "p",
            "desktop",
            "shared",
            ItemType.PACKAGE,
            Instant.now(),
            Optional.empty(),
            Optional.empty()));

    repo.forgetItem("p", new dev.sysboot.core.ModuleName("core"), "shared", ItemType.PACKAGE);

    assertThat(repo.load("p").orElseThrow().entries())
        .extracting(StateEntry::moduleName)
        .containsExactly("desktop");
  }

  @Test
  void forgetPhase_removesOnlyMatchingPhase() {
    var repo = newRepo();
    var first =
        new PhaseStateEntry("base", PhaseStatus.COMPLETED, Instant.now(), Optional.of("base-hash"));
    var second =
        new PhaseStateEntry("apps", PhaseStatus.COMPLETED, Instant.now(), Optional.of("apps-hash"));
    repo.save(BootstrapState.empty("p", "1.0.0").withPhaseEntry(first).withPhaseEntry(second));

    Optional<BootstrapState> updated = repo.forgetPhase("p", "base");

    assertThat(updated).isPresent();
    assertThat(updated.get().phaseEntries())
        .extracting(PhaseStateEntry::phaseName)
        .containsExactly("apps");
    assertThat(repo.load("p").orElseThrow().phaseEntries())
        .extracting(PhaseStateEntry::phaseName)
        .containsExactly("apps");
  }

  @Test
  void stateOperations_whenProfileTraverses_rejectWithoutChangingOutsideFile() throws Exception {
    Path stateRoot = tempDir.resolve("state");
    Path outside = tempDir.resolve("outside.state.json");
    Files.writeString(outside, "outside");
    var repo = testRepo(stateRoot);

    assertThatThrownBy(() -> repo.load("../outside")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repo.reset("../outside")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repo.forgetItem("../outside", "git"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repo.forgetPhase("../outside", "base"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> repo.save(BootstrapState.empty("../outside", "1.0.0")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(outside).hasContent("outside");
  }

  @Test
  void stateOperations_whenCurrentRootIsSymlink_rejectWithoutChangingTarget() throws Exception {
    Path outsideRoot = tempDir.resolve("outside-current");
    Path stateRoot = tempDir.resolve("current-link");
    Files.createDirectories(outsideRoot);
    Path outsideState = outsideRoot.resolve("test-profile.state.json");
    writeEmptyState(outsideState);
    String original = Files.readString(outsideState);
    createSymlinkOrSkip(stateRoot, outsideRoot);
    var repo = testRepo(stateRoot);

    assertThatThrownBy(() -> repo.load("test-profile")).isInstanceOf(StateReadException.class);
    assertThatThrownBy(() -> repo.save(BootstrapState.empty("test-profile", "1.0.0")))
        .isInstanceOf(StateWriteException.class);
    assertThatThrownBy(() -> repo.reset("test-profile")).isInstanceOf(StateWriteException.class);
    assertThatThrownBy(() -> repo.forgetItem("test-profile", "git"))
        .isInstanceOf(StateWriteException.class);
    assertThatThrownBy(() -> repo.forgetPhase("test-profile", "base"))
        .isInstanceOf(StateWriteException.class);
    assertThat(outsideState).hasContent(original);
  }

  @Test
  void stateOperations_whenLegacyRootIsSymlink_rejectWithoutChangingTarget() throws Exception {
    Path currentRoot = tempDir.resolve("current");
    Path outsideRoot = tempDir.resolve("outside-legacy");
    Path legacyRoot = tempDir.resolve("legacy-link");
    Files.createDirectories(currentRoot);
    Files.createDirectories(outsideRoot);
    Path outsideState = outsideRoot.resolve("test-profile.state.json");
    writeEmptyState(outsideState);
    String original = Files.readString(outsideState);
    createSymlinkOrSkip(legacyRoot, outsideRoot);
    var repo = new JsonStateRepository(new StatePaths(currentRoot, legacyRoot), new ObjectMapper());

    assertThatThrownBy(() -> repo.load("test-profile")).isInstanceOf(StateReadException.class);
    assertThatThrownBy(() -> repo.reset("test-profile")).isInstanceOf(StateWriteException.class);
    assertThat(outsideState).hasContent(original);
  }

  private JsonStateRepository repoWithDeniedPermissions(Path stateRoot) {
    return new JsonStateRepository(
        testStatePaths(stateRoot),
        new ObjectMapper(),
        (path, permissions) -> {
          throw new AccessDeniedException(path.toString());
        });
  }

  private JsonStateRepository repoWithPermissionFailure(
      Path stateRoot, RuntimeException permissionFailure) {
    return new JsonStateRepository(
        testStatePaths(stateRoot),
        new ObjectMapper(),
        (path, permissions) -> {
          throw permissionFailure;
        });
  }

  private JsonStateRepository testRepo(Path stateRoot) {
    return new JsonStateRepository(testStatePaths(stateRoot), new ObjectMapper());
  }

  private StatePaths testStatePaths(Path stateRoot) {
    return new StatePaths(stateRoot, tempDir.resolve("isolated-legacy-state"));
  }

  private void writeEmptyState(Path stateFile) throws IOException {
    Files.writeString(
        stateFile,
        """
        {
          "schemaVersion": 2,
          "profileName": "test-profile",
          "entries": [],
          "phaseEntries": []
        }
        """);
  }

  private void writeState(String profileName, String content) throws IOException {
    Files.writeString(tempDir.resolve(profileName + ".state.json"), content);
  }

  private void setPermissions(
      Path path, java.util.Set<java.nio.file.attribute.PosixFilePermission> permissions)
      throws IOException {
    if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
      Files.setPosixFilePermissions(path, permissions);
    }
  }

  private void assumePosix() throws Exception {
    Assumptions.assumeTrue(
        Files.getFileStore(tempDir).supportsFileAttributeView(PosixFileAttributeView.class));
  }

  private void createSymlinkOrSkip(Path link, Path target) {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      Assumptions.abort("Symbolic links are not supported: " + e.getMessage());
    }
  }

  private List<String> directoryLayout(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      return paths.map(root::relativize).map(Path::toString).sorted().toList();
    }
  }
}
