package dev.sysboot.executor;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sysboot.core.BootstrapState;
import dev.sysboot.core.FluxionVersion;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.StateEntry;
import dev.sysboot.core.StateRepository;
import dev.sysboot.executor.state.record.BootstrapStateRecord;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

public final class JsonStateRepository implements StateRepository {

  private final StatePaths statePaths;
  private final ObjectMapper objectMapper;
  private final ProfileStateLock profileLock;
  private final SecureStateFileReader stateFileReader;
  private final StatePathSecurity pathSecurity;
  private final boolean readOnlyLoads;

  public JsonStateRepository(ObjectMapper objectMapper) {
    this(new StatePaths(), objectMapper, false);
  }

  public JsonStateRepository(ObjectMapper objectMapper, boolean readOnlyLoads) {
    this(new StatePaths(), objectMapper, readOnlyLoads);
  }

  JsonStateRepository(Path baseDir, ObjectMapper objectMapper) {
    this(new StatePaths(baseDir), objectMapper, false);
  }

  JsonStateRepository(StatePaths statePaths, ObjectMapper objectMapper) {
    this(statePaths, objectMapper, false);
  }

  private JsonStateRepository(
      StatePaths statePaths, ObjectMapper objectMapper, boolean readOnlyLoads) {
    this(
        statePaths,
        objectMapper,
        StatePathSecurity::setPrivatePermissions,
        SecureStateFileReader::requireMatchingDirectoryOwner,
        readOnlyLoads);
  }

  JsonStateRepository(
      StatePaths statePaths, ObjectMapper objectMapper, StatePermissionSetter permissionSetter) {
    this(
        statePaths,
        objectMapper,
        permissionSetter,
        SecureStateFileReader::requireMatchingDirectoryOwner,
        false);
  }

  JsonStateRepository(
      StatePaths statePaths,
      ObjectMapper objectMapper,
      StatePermissionSetter permissionSetter,
      SecureStateFileReader.OwnerVerifier ownerVerifier) {
    this(statePaths, objectMapper, permissionSetter, ownerVerifier, false);
  }

  private JsonStateRepository(
      StatePaths statePaths,
      ObjectMapper objectMapper,
      StatePermissionSetter permissionSetter,
      SecureStateFileReader.OwnerVerifier ownerVerifier,
      boolean readOnlyLoads) {
    this.statePaths = statePaths;
    this.objectMapper = objectMapper;
    this.profileLock = new ProfileStateLock(statePaths);
    this.stateFileReader = new SecureStateFileReader(objectMapper, permissionSetter, ownerVerifier);
    this.pathSecurity = new StatePathSecurity(permissionSetter);
    this.readOnlyLoads = readOnlyLoads;
  }

  @Override
  public Optional<BootstrapState> load(String profileName) {
    if (readOnlyLoads) {
      return loadReadOnlyOrEmpty(profileName);
    }
    return load(profileName, true);
  }

  public Optional<BootstrapState> loadReadOnly(String profileName) {
    return load(profileName, false);
  }

  private Optional<BootstrapState> loadReadOnlyOrEmpty(String profileName) {
    try {
      return loadReadOnly(profileName);
    } catch (StateReadException ignored) {
      return Optional.empty();
    }
  }

  private Optional<BootstrapState> load(String profileName, boolean repairPermissions) {
    Path stateFile = stateFilePath(profileName);
    Path legacyStateFile = statePaths.legacyStateFile(profileName);
    try {
      stateFile = existingStateFile(stateFile, legacyStateFile, repairPermissions);
      if (!existsNoFollow(stateFile)) {
        return Optional.empty();
      }
      prepareExistingStateFile(stateFile);
      BootstrapStateRecord record =
          repairPermissions
              ? stateFileReader.read(stateFile)
              : stateFileReader.readReadOnly(stateFile);
      return Optional.of(StateMapper.fromRecord(record, profileName));
    } catch (IOException
        | DateTimeParseException
        | IllegalArgumentException
        | NullPointerException
        | SecurityException
        | UnsupportedOperationException e) {
      throw new StateReadException("Failed to read state file: " + stateFile, e);
    }
  }

  @Override
  public void save(BootstrapState state) {
    withProfileLock(
        state.profileName(),
        () -> {
          saveUnlocked(state.profileName(), state);
          return null;
        });
  }

  @Override
  public BootstrapState update(
      String profileName, Function<Optional<BootstrapState>, BootstrapState> transition) {
    return withProfileLock(
        profileName,
        () -> {
          BootstrapState updated = transition.apply(load(profileName));
          saveUnlocked(profileName, updated);
          return updated;
        });
  }

  private void saveUnlocked(String requestedProfile, BootstrapState state) {
    requireMatchingIdentity(requestedProfile, state);
    Path stateFile = stateFilePath(requestedProfile);
    Path tempFile = null;
    try {
      Path stateDirectory = PathRequirements.parent(stateFile, "State file");
      pathSecurity.prepareWriteDirectory(stateDirectory);
      pathSecurity.prepareExistingFile(stateFile);
      tempFile = pathSecurity.createPrivateTempFile(stateFile);
      objectMapper
          .writerWithDefaultPrettyPrinter()
          .writeValue(tempFile.toFile(), StateMapper.toRecord(state));
      pathSecurity.enforcePrivateFile(tempFile);
      pathSecurity.requireSafeRoot(stateDirectory);
      Files.move(
          tempFile, stateFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | SecurityException | UnsupportedOperationException e) {
      throw new StateWriteException("Failed to save state for profile: " + requestedProfile, e);
    } finally {
      deleteTempFile(tempFile);
    }
  }

  @Override
  public BootstrapState recordSuccess(String profileName, StateEntry entry) {
    return update(
        profileName,
        current ->
            current
                .orElseGet(() -> BootstrapState.empty(profileName, FluxionVersion.current()))
                .withEntry(entry));
  }

  @Override
  public void reset(String profileName) {
    withGlobalMutationLock(
        () -> {
          withProfileLock(
              profileName,
              () -> {
                deleteIfExists(path(profileName));
                deleteIfExists(legacyPath(profileName));
                return null;
              });
        });
  }

  @Override
  public Optional<BootstrapState> forgetItem(String profileName, String itemKey) {
    return withGlobalMutationLock(
        () ->
            withProfileLock(
                profileName,
                () -> {
                  Optional<BootstrapState> updated =
                      load(profileName).map(state -> state.withoutItem(itemKey));
                  updated.ifPresent(state -> saveUnlocked(profileName, state));
                  return updated;
                }));
  }

  public Optional<BootstrapState> forgetItem(
      String profileName, ModuleName moduleName, String itemKey, ItemType itemType) {
    return withGlobalMutationLock(
        () ->
            withProfileLock(
                profileName,
                () -> {
                  Optional<BootstrapState> updated =
                      load(profileName)
                          .map(state -> state.withoutItem(moduleName, itemKey, itemType));
                  updated.ifPresent(state -> saveUnlocked(profileName, state));
                  return updated;
                }));
  }

  @Override
  public Optional<BootstrapState> forgetPhase(String profileName, String phaseName) {
    return withGlobalMutationLock(
        () ->
            withProfileLock(
                profileName,
                () -> {
                  Optional<BootstrapState> updated =
                      load(profileName).map(state -> state.withoutPhase(phaseName));
                  updated.ifPresent(state -> saveUnlocked(profileName, state));
                  return updated;
                }));
  }

  public void withGlobalMutationLock(Runnable operation) {
    withGlobalMutationLock(
        () -> {
          operation.run();
          return null;
        });
  }

  private <T> T withGlobalMutationLock(Supplier<T> operation) {
    return profileLock.withGlobalApplyLock(operation);
  }

  private <T> T withProfileLock(String profileName, Supplier<T> operation) {
    return profileLock.withLock(profileName, operation);
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

  private Path existingStateFile(Path stateFile, Path legacyStateFile, boolean repairPermissions)
      throws IOException {
    prepareReadDirectory(PathRequirements.parent(stateFile, "State file"), repairPermissions);
    if (existsNoFollow(stateFile)) {
      return stateFile;
    }
    prepareReadDirectory(
        PathRequirements.parent(legacyStateFile, "Legacy state file"), repairPermissions);
    return legacyStateFile;
  }

  private void prepareReadDirectory(Path directory, boolean repairPermissions) throws IOException {
    if (repairPermissions) {
      pathSecurity.prepareReadDirectory(directory);
      return;
    }
    pathSecurity.inspectReadDirectory(directory);
  }

  private void deleteIfExists(Path stateFile) {
    try {
      pathSecurity.prepareDirectoryForDelete(PathRequirements.parent(stateFile, "State file"));
      Files.deleteIfExists(stateFile);
    } catch (IOException | SecurityException | UnsupportedOperationException e) {
      throw new StateWriteException("Failed to delete state file: " + stateFile, e);
    }
  }

  private void prepareExistingStateFile(Path stateFile) throws IOException {
    pathSecurity.prepareExistingFile(stateFile);
  }

  private void requireMatchingIdentity(String requestedProfile, BootstrapState state) {
    if (!requestedProfile.equals(state.profileName())) {
      throw new StateWriteException(
          "State profile does not match requested profile: " + requestedProfile,
          new IllegalArgumentException(state.profileName()));
    }
    if (state.entries().stream().anyMatch(entry -> !requestedProfile.equals(entry.profileName()))) {
      throw new StateWriteException(
          "State entry profile does not match requested profile: " + requestedProfile,
          new IllegalArgumentException(requestedProfile));
    }
  }

  private boolean existsNoFollow(Path path) {
    return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
  }

  private void deleteTempFile(Path tempFile) {
    if (tempFile == null) {
      return;
    }
    try {
      Files.deleteIfExists(tempFile);
    } catch (IOException | SecurityException | UnsupportedOperationException ignored) {
      // Preserve the original state-write failure if cleanup also fails.
    }
  }
}
