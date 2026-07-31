package dev.sysboot.executor;

import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

final class DelegatedBinaryOutputVerifier {

  private static final long MAX_HASH_BYTES = HttpBinaryDownloadClient.MAX_FILE_BYTES;

  private final BinaryFileSystem fileSystem;

  DelegatedBinaryOutputVerifier(BinaryFileSystem fileSystem) {
    this.fileSystem = fileSystem;
  }

  Optional<Snapshot> snapshot(CompiledBinaryModule module) {
    if (!(BinaryProfileTranslator.translate(module) instanceof String)) {
      return Optional.of(Snapshot.empty());
    }
    try {
      return Optional.of(new Snapshot(outputProof(BinaryProfileTranslator.appsBinaryPath(module))));
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  StepResult verify(CompiledBinaryModule module, StepResult result, Snapshot before) {
    if (!(result instanceof StepResult.Success success)) {
      return result;
    }
    try {
      requireOutput(module, before);
      return result;
    } catch (IOException e) {
      return new StepResult.Failure(
          module.binaryName(),
          "binstaller output verification failed: " + e.getMessage(),
          1,
          success.elapsed());
    }
  }

  private void requireOutput(CompiledBinaryModule module, Snapshot before) throws IOException {
    Path canonical = BinaryProfileTranslator.appsBinaryPath(module);
    OutputProof canonicalAfter = outputProof(canonical);
    if (!canonicalAfter.regularFile()) {
      throw new IOException("canonical apps binary was not produced");
    }
    if (canonicalAfter.sha256().equals(before.canonical().sha256())) {
      throw new IOException("canonical apps binary did not change");
    }
    requireConfiguredDirectDigest(module, canonicalAfter);
    requireDeclaredPath(module.installPath(), canonical, "installPath");
    if (module.symlinkPath().isPresent()) {
      requireDeclaredPath(module.symlinkPath().orElseThrow(), canonical, "symlinkPath");
    }
  }

  private void requireConfiguredDirectDigest(
      CompiledBinaryModule module, OutputProof canonicalAfter) throws IOException {
    if (BinaryProfileTranslator.archiveType(module).isPresent() || module.checksum().isEmpty()) {
      return;
    }
    String expected = module.checksum().orElseThrow().value();
    String actual = canonicalAfter.sha256().orElseThrow();
    if (!expected.equalsIgnoreCase(actual)) {
      throw new IOException(
          "canonical apps binary SHA-256 does not match the configured direct-download digest");
    }
  }

  private void requireDeclaredPath(Path declared, Path canonical, String subject)
      throws IOException {
    if (declared.equals(canonical)) {
      return;
    }
    OutputProof proof = outputProof(declared);
    Optional<Path> resolvedTarget =
        proof.linkTarget().isPresent()
            ? Optional.of(resolvedLink(declared, proof.linkTarget().orElseThrow()))
            : Optional.empty();
    boolean canonicalLink =
        proof.symbolicLink() && resolvedTarget.filter(canonical::equals).isPresent();
    if (!canonicalLink) {
      throw new IOException(subject + " is not a symlink to the canonical apps binary");
    }
  }

  private Path resolvedLink(Path link, Path target) throws IOException {
    return (target.isAbsolute()
            ? target
            : PathRequirements.parent(link, "Delegated binary link").resolve(target))
        .toAbsolutePath()
        .normalize();
  }

  private OutputProof outputProof(Path path) throws IOException {
    if (fileSystem.isSymbolicLink(path)) {
      return OutputProof.symlink(fileSystem.readSymbolicLink(path));
    }
    if (!fileSystem.pathEntryExists(path)) {
      return OutputProof.absent();
    }
    if (!fileSystem.isRegularFile(path)) {
      return OutputProof.other();
    }
    try {
      String digest = BinaryDigest.hex(fileSystem, path, "SHA-256", MAX_HASH_BYTES);
      return OutputProof.regular(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  record Snapshot(OutputProof canonical) {

    private static Snapshot empty() {
      return new Snapshot(OutputProof.absent());
    }
  }

  private record OutputProof(
      boolean regularFile,
      boolean symbolicLink,
      Optional<Path> linkTarget,
      Optional<String> sha256) {

    private static OutputProof absent() {
      return new OutputProof(false, false, Optional.empty(), Optional.empty());
    }

    private static OutputProof symlink(Path target) {
      return new OutputProof(false, true, Optional.of(target), Optional.empty());
    }

    private static OutputProof other() {
      return new OutputProof(false, false, Optional.empty(), Optional.empty());
    }

    private static OutputProof regular(String sha256) {
      return new OutputProof(true, false, Optional.empty(), Optional.of(sha256));
    }
  }
}
