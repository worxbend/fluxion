package dev.sysboot.executor;

import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BinaryInstallTransaction {

  private static final Logger log = LoggerFactory.getLogger(BinaryInstallTransaction.class);

  private final ShellRunner shellRunner;
  private final BinaryFileSystem fileSystem;
  private final PrivilegedArtifactPublisher publisher;

  BinaryInstallTransaction(ShellRunner shellRunner, BinaryFileSystem fileSystem) {
    this(shellRunner, fileSystem, new PrivilegedAtomicFilePublisher(shellRunner));
  }

  BinaryInstallTransaction(
      ShellRunner shellRunner, BinaryFileSystem fileSystem, PrivilegedArtifactPublisher publisher) {
    this.shellRunner = shellRunner;
    this.fileSystem = fileSystem;
    this.publisher = publisher;
  }

  void install(Path source, CompiledBinaryModule module, Optional<Sha256Digest> trustedDigest)
      throws IOException {
    requireDisjointDestinations(module);
    if (privilegeFor(module.installPath()) == InstallPrivilege.ROOT) {
      installPrivileged(source, module, trustedDigest);
      return;
    }
    installUnprivileged(source, module);
  }

  private void requireDisjointDestinations(CompiledBinaryModule module) throws IOException {
    if (module.symlinkPath().isEmpty()) {
      return;
    }
    Path binary = fileSystem.resolvePhysicalEntry(module.installPath());
    Path link = fileSystem.resolvePhysicalEntry(module.symlinkPath().orElseThrow());
    if (binary.startsWith(link) || link.startsWith(binary)) {
      throw new IOException("Install path and symlink path resolve to overlapping destinations");
    }
  }

  boolean requiresPrivilege(Path destination) throws IOException {
    return privilegeFor(destination) == InstallPrivilege.ROOT;
  }

  private void installUnprivileged(Path source, CompiledBinaryModule module) throws IOException {
    Path destination = module.installPath();
    Path staged =
        fileSystem.createTempFile(
            PathRequirements.parent(destination, "Binary destination"),
            ".sysboot-binary-",
            "-" + module.binaryName());
    boolean installed = false;
    Throwable primaryFailure = null;
    Optional<SymlinkChange> symlinkChange = Optional.empty();
    Optional<BinaryChange> binaryChange = Optional.empty();
    try {
      symlinkChange = prepareSymlink(module);
      fileSystem.copy(source, staged);
      applyMode(staged, module.installMode());
      binaryChange = Optional.of(replaceBinary(staged, destination, InstallPrivilege.USER));
      installed = true;
      symlinkChange = commitSymlink(symlinkChange);
      completeBinary(binaryChange);
      completeSymlink(symlinkChange);
    } catch (IOException | RuntimeException failure) {
      primaryFailure = failure;
      rollbackSymlink(symlinkChange, failure);
      rollbackBinary(binaryChange, failure);
      throw failure;
    } finally {
      if (!installed) {
        FailurePreservingCleanup.run(primaryFailure, () -> fileSystem.deleteIfExists(staged));
      }
    }
  }

  private void installPrivileged(
      Path source, CompiledBinaryModule module, Optional<Sha256Digest> trustedDigest)
      throws IOException {
    Optional<SymlinkChange> symlinkChange = Optional.empty();
    var binaryChange = new AtomicReference<BinaryChange>();
    try {
      symlinkChange = prepareSymlink(module);
      Sha256Digest expected =
          trustedDigest.orElseThrow(
              () ->
                  new IOException(
                      "Privileged binary installation requires a checksum-bound artifact"));
      ProcessResult result =
          publisher.consumeVerified(
              source,
              module.installPath(),
              module.installMode().orElse("0755"),
              expected,
              staged -> commitPrivilegedBinary(staged, module, binaryChange));
      if (!result.isSuccess()) {
        throw new IOException(
            "Privileged binary publication failed: " + StepOutcome.detail(result));
      }
      symlinkChange = commitSymlink(symlinkChange);
      completeBinary(Optional.ofNullable(binaryChange.get()));
      completeSymlink(symlinkChange);
    } catch (IOException | RuntimeException failure) {
      rollbackSymlink(symlinkChange, failure);
      rollbackBinary(Optional.ofNullable(binaryChange.get()), failure);
      throw failure;
    }
  }

  private ProcessResult commitPrivilegedBinary(
      Path staged, CompiledBinaryModule module, AtomicReference<BinaryChange> binaryChange)
      throws IOException {
    binaryChange.set(replaceBinary(staged, module.installPath(), InstallPrivilege.ROOT));
    return new ProcessResult(0, "", "", Duration.ZERO);
  }

  private BinaryChange replaceBinary(Path staged, Path destination, InstallPrivilege privilege)
      throws IOException {
    Optional<Backup> backup =
        fileSystem.pathEntryExists(destination)
            ? Optional.of(createBackup(destination, privilege))
            : Optional.empty();
    try {
      movePath(staged, destination, privilege);
      return new BinaryChange(destination, backup, privilege);
    } catch (IOException | RuntimeException failure) {
      discardOrRestoreBackup(destination, backup, privilege, failure);
      throw failure;
    }
  }

  private Backup createBackup(Path destination, InstallPrivilege privilege) throws IOException {
    Path backup = transactionPath(destination, "binary-backup");
    if (fileSystem.isRegularFile(destination)) {
      createHardLink(backup, destination, privilege);
      return new Backup(backup);
    }
    if (!fileSystem.isSymbolicLink(destination)) {
      throw new IOException("Refusing to replace non-file binary destination: " + destination);
    }
    String originalIdentity = fileSystem.fileIdentity(destination);
    try {
      movePath(destination, backup, privilege);
    } catch (IOException | RuntimeException failure) {
      restoreTentativeBackup(destination, backup, privilege, originalIdentity, failure);
      throw failure;
    }
    return new Backup(backup);
  }

  private void restoreTentativeBackup(
      Path destination,
      Path backup,
      InstallPrivilege privilege,
      String originalIdentity,
      Throwable failure) {
    restoreBackup(destination, backup, privilege, originalIdentity, failure);
  }

  private void createHardLink(Path link, Path existing, InstallPrivilege privilege)
      throws IOException {
    if (privilege == InstallPrivilege.ROOT) {
      runSudo(
          List.of(
              "sudo",
              TrustedSystemExecutable.link().toString(),
              "--",
              existing.toString(),
              link.toString()));
      return;
    }
    fileSystem.createHardLink(link, existing);
  }

  private void discardOrRestoreBackup(
      Path destination, Optional<Backup> backup, InstallPrivilege privilege, Throwable failure) {
    if (backup.isEmpty()) {
      removeCreatedDestination(destination, privilege, failure);
      return;
    }
    Path backupPath = backup.orElseThrow().path();
    try {
      restoreBackup(
          destination, backupPath, privilege, fileSystem.fileIdentity(backupPath), failure);
    } catch (IOException | RuntimeException identityFailure) {
      failure.addSuppressed(identityFailure);
    }
  }

  private void deleteSameIdentityBackup(Path destination, Path backup, InstallPrivilege privilege)
      throws IOException {
    if (fileSystem.pathEntryExists(backup)
        && fileSystem.fileIdentity(destination).equals(fileSystem.fileIdentity(backup))) {
      deletePath(backup, privilege);
    }
  }

  private void rollbackBinary(Optional<BinaryChange> change, Throwable failure) {
    if (change.isEmpty()) {
      return;
    }
    BinaryChange value = change.orElseThrow();
    if (value.backup().isPresent()) {
      restoreBinaryBackup(value, failure);
      return;
    }
    removeCreatedDestination(value.destination(), value.privilege(), failure);
  }

  private void removeCreatedDestination(
      Path destination, InstallPrivilege privilege, Throwable failure) {
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        deletePath(destination, privilege);
        return;
      } catch (IOException | RuntimeException rollbackFailure) {
        if (!fileSystem.pathEntryExists(destination)) {
          return;
        }
        if (attempt == 1) {
          failure.addSuppressed(rollbackFailure);
        }
      }
    }
  }

  private void restoreBinaryBackup(BinaryChange change, Throwable failure) {
    Path backup = change.backup().orElseThrow().path();
    String originalIdentity;
    try {
      originalIdentity = fileSystem.fileIdentity(backup);
    } catch (IOException | RuntimeException identityFailure) {
      failure.addSuppressed(identityFailure);
      return;
    }
    restoreBackup(change.destination(), backup, change.privilege(), originalIdentity, failure);
  }

  private void restoreBackup(
      Path destination,
      Path backup,
      InstallPrivilege privilege,
      String originalIdentity,
      Throwable failure) {
    if (destinationHasIdentity(destination, originalIdentity)) {
      deletePathPreserving(backup, privilege, failure);
      return;
    }
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        movePath(backup, destination, privilege);
        deleteSameIdentityBackup(destination, backup, privilege);
        return;
      } catch (IOException | RuntimeException rollbackFailure) {
        if (destinationHasIdentity(destination, originalIdentity)) {
          deletePathPreserving(backup, privilege, failure);
          return;
        }
        if (attempt == 1) {
          failure.addSuppressed(rollbackFailure);
        }
      }
    }
  }

  private boolean destinationHasIdentity(Path destination, String expectedIdentity) {
    try {
      return fileSystem.pathEntryExists(destination)
          && fileSystem.fileIdentity(destination).equals(expectedIdentity);
    } catch (IOException | RuntimeException ignored) {
      return false;
    }
  }

  private void completeBinary(Optional<BinaryChange> change) {
    if (change.isEmpty() || change.orElseThrow().backup().isEmpty()) {
      return;
    }
    BinaryChange value = change.orElseThrow();
    try {
      deletePath(value.backup().orElseThrow().path(), value.privilege());
    } catch (IOException | RuntimeException e) {
      log.warn("Failed to delete binary backup: {}", value.backup().orElseThrow().path());
    }
  }

  private void applyMode(Path destination, Optional<String> mode) throws IOException {
    if (mode.isPresent()) {
      fileSystem.setMode(destination, mode.orElseThrow());
    }
  }

  private Optional<SymlinkChange> prepareSymlink(CompiledBinaryModule module) throws IOException {
    if (module.symlinkPath().isEmpty()) {
      return Optional.empty();
    }
    Path link = module.symlinkPath().orElseThrow();
    InstallPrivilege privilege = privilegeFor(link);
    Path staged = transactionPath(link, "link");
    if (fileSystem.pathEntryExists(link) && !fileSystem.isSymbolicLink(link)) {
      throw new IOException("Refusing to replace non-symlink link destination: " + link);
    }
    Optional<Path> backup =
        fileSystem.pathEntryExists(link)
            ? Optional.of(transactionPath(link, "link-backup"))
            : Optional.empty();
    try {
      createSymlink(staged, module.installPath(), privilege);
      return Optional.of(new SymlinkChange(link, staged, backup, privilege, false));
    } catch (IOException | RuntimeException failure) {
      deletePathPreserving(staged, privilege, failure);
      throw failure;
    }
  }

  private void createSymlink(Path link, Path target, InstallPrivilege privilege)
      throws IOException {
    if (privilege == InstallPrivilege.ROOT) {
      createPrivilegedSymlink(link, target);
    } else {
      fileSystem.createSymlink(link, target);
    }
  }

  private void createPrivilegedSymlink(Path link, Path target) throws IOException {
    Path staged = transactionPath(link, "link");
    boolean installed = false;
    Throwable primaryFailure = null;
    try {
      runSudo(List.of("sudo", "ln", "-s", "--", target.toString(), staged.toString()));
      movePrivileged(staged, link);
      installed = true;
    } catch (IOException | RuntimeException failure) {
      primaryFailure = failure;
      throw failure;
    } finally {
      if (!installed) {
        FailurePreservingCleanup.run(
            primaryFailure, () -> runSudo(List.of("sudo", "rm", "-f", "--", staged.toString())));
      }
    }
  }

  private InstallPrivilege privilegeFor(Path path) throws IOException {
    Path parent = path.getParent();
    if (parent == null) {
      throw new IOException("Install destination has no parent: " + path);
    }
    if (fileSystem.isWritable(parent)) {
      return InstallPrivilege.USER;
    }
    if (fileSystem.isRootOwned(parent) && fileSystem.isSecurePrivilegedDirectory(parent)) {
      return InstallPrivilege.ROOT;
    }
    throw new IOException("Refusing unsafe or untrusted destination parent: " + parent);
  }

  private void movePath(Path source, Path destination, InstallPrivilege privilege)
      throws IOException {
    if (privilege == InstallPrivilege.ROOT) {
      movePrivileged(source, destination);
    } else {
      fileSystem.atomicMoveReplace(source, destination);
    }
  }

  private void movePrivileged(Path source, Path destination) throws IOException {
    runSudo(
        List.of(
            "sudo",
            TrustedSystemExecutable.move().toString(),
            "-fT",
            "--",
            source.toString(),
            destination.toString()));
  }

  private Optional<SymlinkChange> commitSymlink(Optional<SymlinkChange> change) throws IOException {
    if (change.isEmpty()) {
      return change;
    }
    SymlinkChange value = change.orElseThrow();
    try {
      if (value.backup().isPresent()) {
        movePath(value.link(), value.backup().orElseThrow(), value.privilege());
      }
      movePath(value.staged(), value.link(), value.privilege());
    } catch (IOException | RuntimeException failure) {
      restorePreviousSymlink(value, failure);
      throw new IOException("Failed to commit binary symlink", failure);
    }
    return Optional.of(value.committed());
  }

  private void rollbackSymlink(Optional<SymlinkChange> change, Throwable failure) {
    if (change.isEmpty()) {
      return;
    }
    try {
      SymlinkChange value = change.orElseThrow();
      if (value.backup().isPresent() && fileSystem.pathEntryExists(value.backup().orElseThrow())) {
        deletePath(value.link(), value.privilege());
        movePath(value.backup().orElseThrow(), value.link(), value.privilege());
        return;
      }
      if (value.isCommitted()) {
        deletePath(value.link(), value.privilege());
      } else {
        deletePath(value.staged(), value.privilege());
      }
    } catch (IOException | RuntimeException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private void completeSymlink(Optional<SymlinkChange> change) {
    if (change.isEmpty() || change.orElseThrow().backup().isEmpty()) {
      return;
    }
    SymlinkChange value = change.orElseThrow();
    try {
      deletePath(value.backup().orElseThrow(), value.privilege());
    } catch (IOException | RuntimeException e) {
      log.warn("Failed to delete symlink backup: {}", value.backup().orElseThrow());
    }
  }

  private void restorePreviousSymlink(SymlinkChange change, Throwable failure) {
    deletePathPreserving(change.staged(), change.privilege(), failure);
    if (change.backup().isEmpty()) {
      deletePathPreserving(change.link(), change.privilege(), failure);
      return;
    }
    try {
      Path backup = change.backup().orElseThrow();
      if (!fileSystem.pathEntryExists(backup)) {
        return;
      }
      deletePath(change.link(), change.privilege());
      movePath(backup, change.link(), change.privilege());
    } catch (IOException | RuntimeException restoreFailure) {
      failure.addSuppressed(restoreFailure);
    }
  }

  private void deletePathPreserving(Path path, InstallPrivilege privilege, Throwable failure) {
    try {
      deletePath(path, privilege);
    } catch (IOException | RuntimeException cleanupFailure) {
      failure.addSuppressed(cleanupFailure);
    }
  }

  private void deletePath(Path path, InstallPrivilege privilege) throws IOException {
    if (privilege == InstallPrivilege.ROOT) {
      runSudo(List.of("sudo", "rm", "-f", "--", path.toString()));
    } else {
      fileSystem.deleteIfExists(path);
    }
  }

  private Path transactionPath(Path destination, String kind) {
    try {
      return PathRequirements.parent(destination, "Binary destination")
          .resolve(".sysboot-" + kind + "-" + UUID.randomUUID());
    } catch (IOException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  private ProcessResult runSudo(List<String> command) throws IOException {
    ProcessResult result = shellRunner.run(command, Map.of(), Duration.ofMinutes(1));
    if (result.exitCode() != 0) {
      throw new IOException("Command failed: " + String.join(" ", command));
    }
    return result;
  }

  private enum InstallPrivilege {
    USER,
    ROOT
  }

  private record SymlinkChange(
      Path link,
      Path staged,
      Optional<Path> backup,
      InstallPrivilege privilege,
      boolean isCommitted) {

    private SymlinkChange committed() {
      return new SymlinkChange(link, staged, backup, privilege, true);
    }
  }

  private record BinaryChange(
      Path destination, Optional<Backup> backup, InstallPrivilege privilege) {}

  private record Backup(Path path) {}
}
