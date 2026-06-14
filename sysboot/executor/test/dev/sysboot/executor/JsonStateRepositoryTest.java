package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.StateEntry;
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
  void saveAndLoad_roundTrip_preservesEntries() {
    StateEntry entry =
        new StateEntry(
            "test-profile",
            "core-tools",
            "git",
            ItemType.PACKAGE,
            Instant.parse("2026-06-01T10:00:00Z"),
            "2.45.1",
            null);
    BootstrapState state = BootstrapState.empty("test-profile", "1.0.0").withEntry(entry);

    var repo = newRepo();
    repo.save(state);

    Optional<BootstrapState> loaded = repo.load("test-profile");
    assertThat(loaded).isPresent();
    assertThat(loaded.get().profileName()).isEqualTo("test-profile");
    assertThat(loaded.get().entries()).hasSize(1);
    assertThat(loaded.get().entries().get(0).itemKey()).isEqualTo("git");
    assertThat(loaded.get().entries().get(0).itemType()).isEqualTo(ItemType.PACKAGE);
    assertThat(loaded.get().entries().get(0).version()).isEqualTo("2.45.1");
  }

  @Test
  void recordSuccess_addsEntryToExistingState() {
    var repo = newRepo();
    StateEntry first =
        new StateEntry("p", "m", "curl", ItemType.PACKAGE, Instant.now(), null, null);
    repo.recordSuccess("p", first);

    StateEntry second =
        new StateEntry("p", "m", "wget", ItemType.PACKAGE, Instant.now(), null, null);
    repo.recordSuccess("p", second);

    Optional<BootstrapState> state = repo.load("p");
    assertThat(state).isPresent();
    assertThat(state.get().entries()).hasSize(2);
  }

  @Test
  void recordSuccess_upsertsByItemKey() {
    var repo = newRepo();
    StateEntry v1 =
        new StateEntry("p", "m", "git", ItemType.PACKAGE, Instant.now(), "2.40.0", null);
    repo.recordSuccess("p", v1);

    StateEntry v2 =
        new StateEntry("p", "m", "git", ItemType.PACKAGE, Instant.now(), "2.45.1", null);
    repo.recordSuccess("p", v2);

    Optional<BootstrapState> state = repo.load("p");
    assertThat(state.get().entries()).hasSize(1);
    assertThat(state.get().entries().get(0).version()).isEqualTo("2.45.1");
  }

  @Test
  void save_multipleProfiles_areIsolated() {
    var repo = newRepo();
    StateEntry e1 =
        new StateEntry("alpha", "m", "git", ItemType.PACKAGE, Instant.now(), null, null);
    StateEntry e2 =
        new StateEntry("beta", "m", "curl", ItemType.PACKAGE, Instant.now(), null, null);
    repo.recordSuccess("alpha", e1);
    repo.recordSuccess("beta", e2);

    assertThat(repo.load("alpha").get().entries()).hasSize(1);
    assertThat(repo.load("beta").get().entries()).hasSize(1);
    assertThat(repo.load("alpha").get().entries().get(0).itemKey()).isEqualTo("git");
  }
}
