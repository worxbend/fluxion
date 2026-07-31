package dev.sysboot.executor;

import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.PublicUrl;
import dev.sysboot.core.Sha256Digest;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CompiledBinaryInstaller {

  private static final Logger log = LoggerFactory.getLogger(CompiledBinaryInstaller.class);
  private static final long MAX_HASH_BYTES = HttpBinaryDownloadClient.MAX_FILE_BYTES;

  private final BinaryDownloadClient downloadClient;
  private final BinaryFileSystem fileSystem;
  private final ChecksumResolver checksumResolver;
  private final DetachedSignatureVerifier signatureVerifier;
  private final DelegatingBinaryInstaller delegatingInstaller;
  private final TarGzBinaryExtractor archiveExtractor;
  private final BinaryInstallTransaction installTransaction;
  private final DelegatedBinaryOutputVerifier delegatedOutputVerifier;
  private final DelegatedInstallGuard delegatedInstallGuard;
  private final CompiledBinaryTrustPolicy trustPolicy;
  private final PrivilegedArtifactPublisher publisher;

  /** Production wiring: delegate to binstaller when it can express the step. */
  public CompiledBinaryInstaller(ShellRunner shellRunner) {
    this(
        shellRunner,
        new HttpBinaryDownloadClient(),
        new DefaultBinaryFileSystem(),
        new ChecksumResolver(new HttpBinaryDownloadClient()),
        new DelegatingBinaryInstaller(shellRunner, new BrokeredToolResolver(new ToolBroker())));
  }

  /**
   * Test wiring for the built-in path.
   *
   * <p>Delegation is deliberately absent here. Building it internally made these tests depend on
   * whether the developer's machine happened to have binstaller on PATH: where it did, delegation
   * intercepted, the stubbed runner returned zero, and assertions about checksum verification
   * passed while verifying nothing.
   */
  CompiledBinaryInstaller(ShellRunner shellRunner, ChecksumResolver checksumResolver) {
    this(
        shellRunner,
        new HttpBinaryDownloadClient(),
        new DefaultBinaryFileSystem(),
        checksumResolver,
        DelegatingBinaryInstaller.disabled());
  }

  CompiledBinaryInstaller(
      ShellRunner shellRunner, BinaryDownloadClient downloadClient, BinaryFileSystem fileSystem) {
    this(
        shellRunner,
        downloadClient,
        fileSystem,
        new ChecksumResolver(downloadClient),
        DelegatingBinaryInstaller.disabled());
  }

  CompiledBinaryInstaller(
      ShellRunner shellRunner,
      BinaryDownloadClient downloadClient,
      BinaryFileSystem fileSystem,
      PrivilegedArtifactPublisher publisher) {
    this(
        shellRunner,
        downloadClient,
        fileSystem,
        new ChecksumResolver(downloadClient),
        DelegatingBinaryInstaller.disabled(),
        publisher);
  }

  /** Test wiring for the delegating path. */
  CompiledBinaryInstaller(ShellRunner shellRunner, DelegatingBinaryInstaller delegatingInstaller) {
    this(
        shellRunner,
        new HttpBinaryDownloadClient(),
        new DefaultBinaryFileSystem(),
        new ChecksumResolver(new HttpBinaryDownloadClient()),
        delegatingInstaller);
  }

  CompiledBinaryInstaller(
      ShellRunner shellRunner,
      BinaryFileSystem fileSystem,
      DelegatingBinaryInstaller delegatingInstaller) {
    this(
        shellRunner,
        new HttpBinaryDownloadClient(),
        fileSystem,
        new ChecksumResolver(new HttpBinaryDownloadClient()),
        delegatingInstaller);
  }

  private CompiledBinaryInstaller(
      ShellRunner shellRunner,
      BinaryDownloadClient downloadClient,
      BinaryFileSystem fileSystem,
      ChecksumResolver checksumResolver,
      DelegatingBinaryInstaller delegatingInstaller) {
    this(
        shellRunner,
        downloadClient,
        fileSystem,
        checksumResolver,
        delegatingInstaller,
        new PrivilegedAtomicFilePublisher(shellRunner));
  }

  private CompiledBinaryInstaller(
      ShellRunner shellRunner,
      BinaryDownloadClient downloadClient,
      BinaryFileSystem fileSystem,
      ChecksumResolver checksumResolver,
      DelegatingBinaryInstaller delegatingInstaller,
      PrivilegedArtifactPublisher publisher) {
    this.downloadClient = downloadClient;
    this.fileSystem = fileSystem;
    this.checksumResolver = checksumResolver;
    this.signatureVerifier = new DetachedSignatureVerifier(shellRunner);
    this.delegatingInstaller = delegatingInstaller;
    this.archiveExtractor = new TarGzBinaryExtractor(fileSystem);
    this.publisher = publisher;
    this.installTransaction = new BinaryInstallTransaction(shellRunner, fileSystem, publisher);
    this.delegatedOutputVerifier = new DelegatedBinaryOutputVerifier(fileSystem);
    this.delegatedInstallGuard = new DelegatedInstallGuard(fileSystem);
    this.trustPolicy = new CompiledBinaryTrustPolicy();
  }

  public StepResult install(CompiledBinaryModule module) {
    Optional<String> trustFailure = trustPolicy.failure(module);
    if (trustFailure.isPresent()) {
      return new StepResult.Failure(
          module.binaryName(), trustFailure.orElseThrow(), 1, Duration.ZERO);
    }
    Optional<StepResult> delegated = installDelegated(module);
    if (delegated.isPresent()) {
      return delegated.orElseThrow();
    }
    if (CompiledBinaryArtifactFormat.requiresDelegation(module.url().value())) {
      return delegationFailure(module);
    }
    return installLocally(module);
  }

  private Optional<StepResult> installDelegated(CompiledBinaryModule module) {
    if (!delegatingInstaller.isEnabled()
        || BinaryProfileTranslator.translate(module) instanceof BinaryProfileTranslator.Refusal) {
      return Optional.empty();
    }
    Optional<DelegatedBinaryOutputVerifier.Snapshot> before =
        delegatedOutputVerifier.snapshot(module);
    if (before.isEmpty()) {
      return Optional.of(delegationInspectionFailure(module));
    }
    DelegatedInstallGuard.Snapshot guard;
    try {
      guard = delegatedInstallGuard.prepare(module);
    } catch (IOException | RuntimeException failure) {
      return Optional.of(delegationGuardFailure(module, failure));
    }
    try {
      Optional<StepResult> delegated = delegatingInstaller.install(module);
      if (delegated.isEmpty()) {
        delegatedInstallGuard.commit(guard);
        return Optional.empty();
      }
      StepResult verified =
          delegatedOutputVerifier.verify(module, delegated.orElseThrow(), before.orElseThrow());
      if (verified instanceof StepResult.Success) {
        delegatedInstallGuard.commit(guard);
        return Optional.of(verified);
      }
      return Optional.of(restoreDelegatedFailure(module, verified, guard));
    } catch (RuntimeException failure) {
      StepResult failed = delegationGuardFailure(module, failure);
      return Optional.of(restoreDelegatedFailure(module, failed, guard));
    }
  }

  private StepResult delegationInspectionFailure(CompiledBinaryModule module) {
    return new StepResult.Failure(
        module.binaryName(),
        "Cannot inspect delegated binary destinations before installation",
        1,
        Duration.ZERO);
  }

  private StepResult delegationGuardFailure(CompiledBinaryModule module, Throwable failure) {
    return new StepResult.Failure(
        module.binaryName(),
        "Cannot guard delegated binary outputs: " + failure.getMessage(),
        1,
        Duration.ZERO);
  }

  private StepResult restoreDelegatedFailure(
      CompiledBinaryModule module,
      StepResult delegatedFailure,
      DelegatedInstallGuard.Snapshot guard) {
    try {
      delegatedInstallGuard.restore(guard);
      return delegatedFailure;
    } catch (IOException | RuntimeException restoreFailure) {
      if (delegatedFailure instanceof StepResult.Failure primary) {
        return new StepResult.Failure(
            primary.item(),
            primary.errorMessage() + "; rollback was incomplete: " + restoreFailure.getMessage(),
            primary.exitCode(),
            primary.elapsed());
      }
      return new StepResult.Failure(
          module.binaryName(),
          "Delegated binary installation rollback was incomplete: " + restoreFailure.getMessage(),
          1,
          Duration.ZERO);
    }
  }

  private StepResult delegationFailure(CompiledBinaryModule module) {
    String reason =
        BinaryProfileTranslator.translate(module) instanceof BinaryProfileTranslator.Refusal refusal
            ? "; " + refusal.reason()
            : "";
    return new StepResult.Failure(
        module.binaryName(),
        "Cannot install "
            + module.url().value().getPath()
            + " without binstaller; Fluxion cannot extract this archive format locally"
            + reason,
        1,
        Duration.ZERO);
  }

  private StepResult installLocally(CompiledBinaryModule module) {
    Instant start = Instant.now();
    Path tempFile = null;
    Path extractedFile = null;
    Optional<Path> signatureFile = Optional.empty();
    try {
      tempFile = fileSystem.createTempFile("sysboot-", "-" + module.binaryName());
      Path downloadedFile = tempFile;
      Sha256Digest responseDigest =
          downloadClient.downloadToFileWithDigest(module.url().value(), downloadedFile);
      signatureFile = downloadDetachedSignature(module);
      if (installTransaction.requiresPrivilege(module.installPath())) {
        Optional<Checksum> checksum =
            installPrivilegedArtifact(module, downloadedFile, signatureFile, responseDigest);
        return success(module, start, checksum);
      }
      verifyDetachedSignature(module, downloadedFile, signatureFile);
      Optional<Checksum> checksum = verifyResolvedChecksum(module, downloadedFile);
      Optional<TarGzBinaryExtractor.ExtractedBinary> extracted =
          extractArchive(downloadedFile, module);
      extractedFile = extracted.map(TarGzBinaryExtractor.ExtractedBinary::path).orElse(null);
      Path installSource = extractedFile != null ? extractedFile : downloadedFile;
      installTransaction.install(installSource, module, Optional.empty());
      return success(module, start, checksum);
    } catch (IOException | RuntimeException e) {
      return new StepResult.Failure(
          module.binaryName(), publicFailure(module, e), 1, Duration.between(start, Instant.now()));
    } finally {
      deleteTempFile(tempFile);
      deleteTempFile(extractedFile);
      signatureFile.ifPresent(this::deleteTempFile);
    }
  }

  public List<String> dryRunCommand(CompiledBinaryModule module) {
    Optional<List<String>> delegated = delegatingInstaller.commandPreview(module);
    if (delegated.isPresent()) {
      return delegated.orElseThrow();
    }
    if (CompiledBinaryArtifactFormat.requiresDelegation(module.url().value())) {
      return List.of("refuse", PublicUrl.from(module.url().value()), "requires", "binstaller");
    }
    var command = new ArrayList<String>();
    command.addAll(
        List.of(
            "download",
            PublicUrl.from(module.url().value()),
            "->",
            module.installPath().toString()));
    appendArchivePreview(module, command);
    module.installMode().ifPresent(mode -> command.addAll(List.of("mode", mode)));
    module
        .symlinkPath()
        .ifPresent(
            link ->
                command.addAll(
                    List.of("symlink", link.toString(), "->", module.installPath().toString())));
    return List.copyOf(command);
  }

  private Optional<Checksum> verifyResolvedChecksum(
      CompiledBinaryModule module, Path downloadedFile) throws IOException {
    Optional<Checksum> checksum = checksumResolver.resolve(module);
    if (checksum.isPresent()) {
      verifyChecksum(downloadedFile, checksum.orElseThrow());
    }
    return checksum;
  }

  private StepResult.Success success(
      CompiledBinaryModule module, Instant start, Optional<Checksum> checksum) {
    return new StepResult.Success(
        module.binaryName(),
        Duration.between(start, Instant.now()),
        detectedVersion(module),
        checksum.map(Checksum::value));
  }

  private Optional<Path> downloadDetachedSignature(CompiledBinaryModule module) throws IOException {
    if (module.signatureUrl().isEmpty()) {
      return Optional.empty();
    }
    Path signatureFile = fileSystem.createTempFile("sysboot-", ".sig");
    try {
      downloadClient.downloadToFile(module.signatureUrl().orElseThrow().value(), signatureFile);
      return Optional.of(signatureFile);
    } catch (IOException | RuntimeException e) {
      deleteTempFile(signatureFile);
      throw e;
    }
  }

  private void verifyDetachedSignature(
      CompiledBinaryModule module, Path artifact, Optional<Path> signatureFile) {
    if (signatureFile.isEmpty()) {
      return;
    }
    signatureVerifier.verify(
        signatureFile.orElseThrow(), artifact, module.allowedSignerFingerprint().orElseThrow());
  }

  private void verifyChecksum(Path file, Checksum checksum) {
    try {
      String actual = BinaryDigest.hex(fileSystem, file, checksum.algorithm(), MAX_HASH_BYTES);
      if (!actual.equals(checksum.value())) {
        throw new ShellExecutionException(
            "Checksum mismatch: expected " + checksum.value() + " but got " + actual);
      }
    } catch (NoSuchAlgorithmException e) {
      throw new ShellExecutionException("Unknown checksum algorithm: " + checksum.algorithm(), e);
    } catch (IOException e) {
      throw new ShellExecutionException("Failed to read file for checksum verification", e);
    }
  }

  private void appendArchivePreview(CompiledBinaryModule module, List<String> command) {
    if (!isTarGz(module)) {
      command.add("direct-binary");
      return;
    }
    command.addAll(
        List.of(
            "extract",
            module.archivePath().orElseThrow(),
            "strip-components",
            Integer.toString(module.stripComponents())));
  }

  private Optional<String> detectedVersion(CompiledBinaryModule module) {
    InstallationStatus status =
        new CompiledBinaryProbe(module.versionCommand(), module.expectedVersion())
            .probeTrustedInstalled(module);
    if (status instanceof InstallationStatus.InstalledByProbe installed) {
      return Optional.ofNullable(installed.detectedVersion());
    }
    return Optional.empty();
  }

  private Optional<TarGzBinaryExtractor.ExtractedBinary> extractArchive(
      Path downloadedFile, CompiledBinaryModule module) throws IOException {
    if (!isTarGz(module)) {
      return Optional.empty();
    }
    return Optional.of(archiveExtractor.extract(downloadedFile, module));
  }

  private Optional<Checksum> installPrivilegedArtifact(
      CompiledBinaryModule module,
      Path downloadedFile,
      Optional<Path> signatureFile,
      Sha256Digest responseDigest)
      throws IOException {
    var resolvedChecksum = new AtomicReference<Optional<Checksum>>(Optional.empty());
    var prepared = new AtomicReference<PreparedArtifact>();
    try {
      var result =
          publisher.consumeVerified(
              downloadedFile,
              module.installPath(),
              "0444",
              responseDigest,
              staged -> {
                verifyDetachedSignature(module, staged, signatureFile);
                Optional<Checksum> checksum = verifyResolvedChecksum(module, staged);
                resolvedChecksum.set(checksum);
                prepared.set(preparePrivilegedSource(staged, module, responseDigest));
                return new dev.sysboot.core.ProcessResult(0, "", "", Duration.ZERO);
              });
      if (!result.isSuccess() || prepared.get() == null) {
        throw new IOException(
            "Privileged artifact staging or cleanup failed: " + StepOutcome.detail(result));
      }
      PreparedArtifact artifact = prepared.get();
      installTransaction.install(artifact.path(), module, Optional.of(artifact.digest()));
      return resolvedChecksum.get();
    } finally {
      Optional.ofNullable(prepared.get())
          .map(PreparedArtifact::path)
          .ifPresent(this::deleteTempFile);
    }
  }

  private PreparedArtifact preparePrivilegedSource(
      Path staged, CompiledBinaryModule module, Sha256Digest responseDigest) throws IOException {
    Optional<TarGzBinaryExtractor.ExtractedBinary> extracted = extractArchive(staged, module);
    if (extracted.isPresent()) {
      TarGzBinaryExtractor.ExtractedBinary binary = extracted.orElseThrow();
      return new PreparedArtifact(binary.path(), binary.digest());
    }
    Path copied = fileSystem.createTempFile("sysboot-verified-", "-" + module.binaryName());
    try {
      fileSystem.copy(staged, copied);
      return new PreparedArtifact(copied, responseDigest);
    } catch (IOException | RuntimeException failure) {
      deleteTempFile(copied);
      throw failure;
    }
  }

  private record PreparedArtifact(Path path, Sha256Digest digest) {}

  private boolean isTarGz(CompiledBinaryModule module) {
    String urlString = module.url().value().getPath().toLowerCase(Locale.ROOT);
    return urlString.endsWith(".tar.gz") || urlString.endsWith(".tgz");
  }

  private void deleteTempFile(Path tempFile) {
    if (tempFile != null) {
      try {
        fileSystem.deleteIfExists(tempFile);
      } catch (IOException | RuntimeException e) {
        log.warn("Failed to delete temp file: {}", tempFile);
      }
    }
  }

  private String publicFailure(CompiledBinaryModule module, Throwable failure) {
    String detail = failure.getMessage();
    String sanitized = detail == null ? "Compiled binary installation failed" : detail;
    sanitized = replaceUrl(sanitized, module.url());
    for (dev.sysboot.core.BinaryUrl url :
        java.util.stream.Stream.concat(
                module.checksumUrl().stream(), module.signatureUrl().stream())
            .toList()) {
      sanitized = replaceUrl(sanitized, url);
    }
    return failure.getSuppressed().length == 0
        ? sanitized
        : sanitized + " (cleanup or rollback incomplete)";
  }

  private String replaceUrl(String detail, dev.sysboot.core.BinaryUrl url) {
    return detail.replace(url.value().toString(), PublicUrl.from(url.value()));
  }
}
