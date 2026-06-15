package dev.sysboot.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.executor.state.record.BootstrapStateRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeParseException;
import java.util.Optional;

public final class JsonStateRepository implements StateRepository {

  private final StatePaths statePaths;
  private final ObjectMapper objectMapper;

  public JsonStateRepository(ObjectMapper objectMapper) {
    this(new StatePaths(), objectMapper);
  }

  JsonStateRepository(Path baseDir, ObjectMapper objectMapper) {
    this(new StatePaths(baseDir), objectMapper);
  }

  JsonStateRepository(StatePaths statePaths, ObjectMapper objectMapper) {
    this.statePaths = statePaths;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<BootstrapState> load(String profileName) {
    Path stateFile = existingStateFile(profileName);
    if (!Files.exists(stateFile)) {
      return Optional.empty();
    }
    try {
      BootstrapStateRecord record =
          objectMapper.readValue(stateFile.toFile(), BootstrapStateRecord.class);
      return Optional.of(StateMapper.fromRecord(record));
    } catch (IOException | DateTimeParseException | IllegalArgumentException e) {
      throw new StateReadException("Failed to read state file: " + stateFile, e);
    }
  }

  @Override
  public void save(BootstrapState state) {
    Path stateFile = stateFilePath(state.profileName());
    Path tempFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
    try {
      Files.createDirectories(statePaths.baseDir());
      objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(tempFile.toFile(), StateMapper.toRecord(state));
      Files.move(
          tempFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new StateWriteException("Failed to save state for profile: " + state.profileName(), e);
    }
  }

  @Override
  public BootstrapState recordSuccess(String profileName, StateEntry entry) {
    BootstrapState current = load(profileName).orElse(BootstrapState.empty(profileName, "1.0.0"));
    BootstrapState updated = current.withEntry(entry);
    save(updated);
    return updated;
  }

  @Override
  public void reset(String profileName) {
    deleteIfExists(path(profileName));
    deleteIfExists(legacyPath(profileName));
  }

  @Override
  public Optional<BootstrapState> forgetItem(String profileName, String itemKey) {
    Optional<BootstrapState> current = load(profileName);
    Optional<BootstrapState> updated = current.map(state -> state.withoutItem(itemKey));
    updated.ifPresent(this::save);
    return updated;
  }

  @Override
  public Optional<BootstrapState> forgetPhase(String profileName, String phaseName) {
    Optional<BootstrapState> current = load(profileName);
    Optional<BootstrapState> updated = current.map(state -> state.withoutPhase(phaseName));
    updated.ifPresent(this::save);
    return updated;
  }

  private Path stateFilePath(String profileName) {
    return statePaths.stateFile(profileName);
  }

  public Path path(String profileName) {
    return stateFilePath(profileName);
  }

  public Path legacyPath(String profileName) {
    return statePaths.legacyStateFile(profileName);
  }

  private Path existingStateFile(String profileName) {
    Path stateFile = stateFilePath(profileName);
    if (Files.exists(stateFile)) {
      return stateFile;
    }
    return statePaths.legacyStateFile(profileName);
  }

  private void deleteIfExists(Path stateFile) {
    try {
      Files.deleteIfExists(stateFile);
    } catch (IOException e) {
      throw new StateWriteException("Failed to delete state file: " + stateFile, e);
    }
  }
}
