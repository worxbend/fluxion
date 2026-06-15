package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.PhaseStateEntry;
import dev.sysboot.core.PhaseStatus;
import dev.sysboot.core.StateEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonStateRepositoryTest {

  @TempDir Path tempDir;

  private JsonStateRepository newRepo() {
    return new JsonStateRepository(tempDir, new ObjectMapper());
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
}
