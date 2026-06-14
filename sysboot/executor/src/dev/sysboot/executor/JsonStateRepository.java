package dev.sysboot.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.executor.dto.BootstrapStateDto;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class JsonStateRepository implements StateRepository {

  private static final String STATE_DIR = ".local/share/sysboot";
  private static final String FILE_SUFFIX = ".state.json";

  private final Path baseDir;
  private final ObjectMapper objectMapper;

  public JsonStateRepository(ObjectMapper objectMapper) {
    this.baseDir = Path.of(System.getProperty("user.home")).resolve(STATE_DIR);
    this.objectMapper = objectMapper;
  }

  JsonStateRepository(Path baseDir, ObjectMapper objectMapper) {
    this.baseDir = baseDir;
    this.objectMapper = objectMapper;
  }

  @Override
  public Optional<BootstrapState> load(String profileName) {
    Path stateFile = stateFilePath(profileName);
    if (!Files.exists(stateFile)) {
      return Optional.empty();
    }
    try {
      BootstrapStateDto dto = objectMapper.readValue(stateFile.toFile(), BootstrapStateDto.class);
      return Optional.of(StateMapper.fromDto(dto));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  @Override
  public void save(BootstrapState state) {
    Path stateFile = stateFilePath(state.profileName());
    Path tempFile = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
    try {
      Files.createDirectories(baseDir);
      objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(tempFile.toFile(), StateMapper.toDto(state));
      Files.move(
          tempFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new StateWriteException("Failed to save state for profile: " + state.profileName(), e);
    }
  }

  @Override
  public void recordSuccess(String profileName, StateEntry entry) {
    BootstrapState current = load(profileName).orElse(BootstrapState.empty(profileName, "1.0.0"));
    save(current.withEntry(entry));
  }

  private Path stateFilePath(String profileName) {
    return baseDir.resolve(profileName + FILE_SUFFIX);
  }
}
