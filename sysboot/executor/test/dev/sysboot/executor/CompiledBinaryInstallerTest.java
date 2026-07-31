package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.ProcessResult;
import dev.sysboot.core.ShellRunner;
import dev.sysboot.core.StepResult;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CompiledBinaryInstallerTest {

  @TempDir private Path tempDir;

  @Test
  void install_whenDirectBinaryDownloaded_writesModeAndSymlink() throws Exception {
    byte[] body = "#!/bin/sh\necho rg\n".getBytes();
    Path installPath = tempDir.resolve("bin/rg");
    Path symlink = tempDir.resolve("bin/ripgrep");
    Files.createDirectories(installPath.getParent());
    var installer = installer(Map.of(directUri(), body));

    StepResult result = installer.install(module(directUri(), installPath, sha256(body), symlink));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(Files.readAllBytes(installPath)).isEqualTo(body);
    assertThat(Files.readSymbolicLink(symlink)).isEqualTo(installPath);
    assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(installPath)))
        .isEqualTo("rwxr-x---");
  }

  @Test
  void install_whenTarGzDownloaded_extractsSelectedPathWithStripComponents() throws Exception {
    byte[] body = "archive-rg".getBytes();
    byte[] archive = tarGz("ripgrep-1.0/bin/rg", body);
    Path installPath = tempDir.resolve("rg");
    var installer = installer(Map.of(archiveUri(), archive));
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(archiveUri()),
            Optional.of(new Checksum("sha256", sha256(archive))),
            Optional.empty(),
            Optional.empty(),
            installPath,
            Optional.of("bin/rg"),
            1,
            Optional.of("0755"),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty());

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(Files.readAllBytes(installPath)).isEqualTo(body);
  }

  @Test
  void install_whenPrivilegedArchiveOriginalIsSwapped_extractsOnlyVerifiedRootStage()
      throws Exception {
    byte[] trustedBody = "trusted-rg".getBytes();
    byte[] trustedArchive = tarGz("rg", trustedBody);
    byte[] attackerArchive = tarGz("rg", "attacker-rg".getBytes());
    Path installPath = tempDir.resolve("rg");
    Path rootArchiveStage = Files.write(tempDir.resolve(".root-archive-stage"), trustedArchive);
    var fileSystem = spy(new DefaultBinaryFileSystem());
    org.mockito.Mockito.doReturn(false).when(fileSystem).isWritable(tempDir);
    org.mockito.Mockito.doReturn(true).when(fileSystem).isRootOwned(tempDir);
    org.mockito.Mockito.doReturn(true).when(fileSystem).isSecurePrivilegedDirectory(tempDir);
    var publisher = new SwappingArchivePublisher(rootArchiveStage, attackerArchive);
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(),
            new FakeDownloadClient(Map.of(archiveUri(), trustedArchive)),
            fileSystem,
            publisher);

    StepResult result =
        installer.install(module(archiveUri(), installPath, sha256(trustedArchive), null));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(publisher.calls).isEqualTo(2);
    assertThat(publisher.binaryBytes).isEqualTo(trustedBody);
  }

  @Test
  void install_whenPrivilegedSignedArtifactChecksumMetadataMutatesSource_failsClosed()
      throws Exception {
    byte[] trustedArchive = tarGz("rg", "trusted".getBytes());
    byte[] attackerArchive = tarGz("rg", "attacker".getBytes());
    URI signatureUri = URI.create("https://example.test/rg.tar.gz.sig");
    URI checksumUri = URI.create("https://example.test/rg.tar.gz.sha256");
    Path installPath = tempDir.resolve("rg");
    var artifactPath = new AtomicReference<Path>();
    BinaryDownloadClient downloadClient =
        mutatingChecksumClient(
            trustedArchive, attackerArchive, signatureUri, checksumUri, artifactPath);
    var fileSystem = privilegedFileSystem();
    var publisher = new ImmutableStagePublisher(tempDir.resolve(".root-stage"));
    var installer =
        new CompiledBinaryInstaller(validSignatureRunner(), downloadClient, fileSystem, publisher);
    var module = signedArchiveModule(installPath, signatureUri, Optional.of(checksumUri));

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("Checksum mismatch");
    assertThat(publisher.binaryBytes).isNull();
    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_whenPrivilegedDirectArtifactHasOnlyValidSignature_derivesStageDigest()
      throws Exception {
    byte[] body = "signed-direct".getBytes();
    URI signatureUri = URI.create("https://example.test/rg.sig");
    Path installPath = tempDir.resolve("rg");
    var publisher = new ImmutableStagePublisher(tempDir.resolve(".root-stage"));
    var installer =
        new CompiledBinaryInstaller(
            validSignatureRunner(),
            new FakeDownloadClient(Map.of(directUri(), body, signatureUri, "signature".getBytes())),
            privilegedFileSystem(),
            publisher);

    StepResult result = installer.install(signedModule(installPath, signatureUri, "A".repeat(40)));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(publisher.binaryBytes).isEqualTo(body);
  }

  @Test
  void install_whenPrivilegedArchiveHasOnlyValidSignature_derivesExtractedDigest()
      throws Exception {
    byte[] body = "signed-archive".getBytes();
    byte[] archive = tarGz("rg", body);
    URI signatureUri = URI.create("https://example.test/rg.tar.gz.sig");
    Path installPath = tempDir.resolve("rg");
    var publisher = new ImmutableStagePublisher(tempDir.resolve(".root-stage"));
    var installer =
        new CompiledBinaryInstaller(
            validSignatureRunner(),
            new FakeDownloadClient(
                Map.of(archiveUri(), archive, signatureUri, "signature".getBytes())),
            privilegedFileSystem(),
            publisher);

    StepResult result =
        installer.install(signedArchiveModule(installPath, signatureUri, Optional.empty()));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    assertThat(publisher.binaryBytes).isEqualTo(body);
  }

  @Test
  void install_whenPrivilegedCleanupIsSuppressed_reportsIncompleteCleanup() throws Exception {
    byte[] body = "trusted".getBytes();
    Path installPath = tempDir.resolve("rg");
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(),
            new FakeDownloadClient(Map.of(directUri(), body)),
            privilegedFileSystem(),
            new SuppressedFailurePublisher());

    StepResult result = installer.install(module(directUri(), installPath, sha256(body), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("primary verification failure")
        .contains("cleanup or rollback incomplete");
  }

  @Test
  void install_whenOuterRootStageCleanupFails_preservesExistingBinary() throws Exception {
    byte[] body = "new".getBytes();
    Path installPath = existingBinary();
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(),
            new FakeDownloadClient(Map.of(directUri(), body)),
            privilegedFileSystem(),
            new OuterCleanupFailingPublisher(tempDir.resolve(".root-stage")));

    StepResult result = installer.install(module(directUri(), installPath, sha256(body), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("artifact verification failed")
        .contains("root-owned artifact stage");
    assertThat(Files.readString(installPath)).isEqualTo("old");
  }

  @Test
  void install_whenChecksumMismatches_failsBeforeDestinationWrite() throws Exception {
    Path installPath = tempDir.resolve("rg");
    var installer = installer(Map.of(directUri(), "bad".getBytes()));

    StepResult result =
        installer.install(module(directUri(), installPath, sha256("good".getBytes()), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("Checksum mismatch");
    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_whenChecksumVerified_hashesArtifactThroughStream() throws Exception {
    byte[] body = "streamed-binary".getBytes();
    Path installPath = tempDir.resolve("rg");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(), new FakeDownloadClient(Map.of(directUri(), body)), fileSystem);

    StepResult result = installer.install(module(directUri(), installPath, sha256(body), null));

    assertThat(result).isInstanceOf(StepResult.Success.class);
    verify(fileSystem).openInput(any(Path.class));
    verify(fileSystem, never()).readAllBytes(any(Path.class));
  }

  @Test
  void install_whenIntegrityMetadataMissing_refusesBeforeDownload() {
    Path installPath = tempDir.resolve("rg");
    var installer = installer(Map.of());
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(directUri()),
            Optional.empty(),
            installPath,
            false);

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("Refusing untrusted");
    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_whenOnlyChecksumUrlConfigured_refusesBeforeDownload() throws Exception {
    Path installPath = tempDir.resolve("rg");
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(directUri()),
            Optional.empty(),
            Optional.of(new BinaryUrl(URI.create("https://example.test/rg.sha256"))),
            installPath,
            false);

    StepResult result = installer(Map.of()).install(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("literal SHA-256")
        .contains("supplemental metadata only");
    assertThat(installPath).doesNotExist();
  }

  @ParameterizedTest
  @ValueSource(strings = {"rg.zip", "rg.tar.xz"})
  void install_whenDelegationRequiredButUnavailable_refusesWithoutRawCopy(String assetName) {
    URI uri = URI.create("https://example.test/" + assetName);
    Path installPath = tempDir.resolve("rg");
    var installer = installer(Map.of());
    var module = module(uri, installPath, "a".repeat(64), null);

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("without binstaller")
        .contains("cannot extract");
    assertThat(installer.dryRunCommand(module))
        .containsExactly("refuse", uri.toString(), "requires", "binstaller");
    assertThat(installPath).doesNotExist();
  }

  @Test
  void previewAndDownloadFailureDoNotDiscloseBinaryUrlRequestData() {
    URI uri = URI.create("https://example.test/rg?token=secret#internal");
    Path installPath = tempDir.resolve("rg");
    BinaryDownloadClient failingDownload =
        new BinaryDownloadClient() {
          @Override
          public void downloadToFile(URI requested, Path destination) throws IOException {
            throw new IOException("Cannot download " + requested);
          }

          @Override
          public String downloadText(URI requested) {
            throw new UnsupportedOperationException("not used");
          }
        };
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(), failingDownload, new DefaultBinaryFileSystem());
    var module = module(uri, installPath, "a".repeat(64), null);

    StepResult result = installer.install(module);

    assertThat(installer.dryRunCommand(module))
        .contains("https://example.test/rg")
        .allSatisfy(value -> assertThat(value).doesNotContain("secret", "internal", "token="));
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("https://example.test/rg")
        .doesNotContain("secret", "internal", "token=");
  }

  @Test
  void install_whenProgrammaticChecksumIsNotSha256_refusesBeforeDownload() {
    Path installPath = tempDir.resolve("rg");
    var installer = installer(Map.of());
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(directUri()),
            Optional.of(new Checksum("md5", "a".repeat(32))),
            Optional.empty(),
            Optional.empty(),
            installPath,
            Optional.empty(),
            0,
            Optional.of("0750"),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty());

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("must use SHA-256");
    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_whenProgrammaticSha256DigestIsMalformed_refusesBeforeDownload() {
    Path installPath = tempDir.resolve("rg");
    var installer = installer(Map.of());
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(directUri()),
            Optional.of(new Checksum("SHA-256", "not-a-digest")),
            Optional.empty(),
            Optional.empty(),
            installPath,
            Optional.empty(),
            0,
            Optional.of("0750"),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty());

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("64-character");
    assertThat(installPath).doesNotExist();
  }

  @Test
  void install_whenDetachedSignatureUsesWrongSigner_failsBeforeDestinationWrite() {
    byte[] artifact = "binary".getBytes();
    URI signatureUri = URI.create("https://example.test/rg.sig");
    Path installPath = tempDir.resolve("rg");
    var runner =
        new FixedRunner(
            new ProcessResult(
                0,
                "[GNUPG:] VALIDSIG " + "B".repeat(40) + " 0 0 0 4 0 1 10 00\n",
                "",
                Duration.ZERO));
    var fileSystem = spy(new DefaultBinaryFileSystem());
    try {
      doAnswer(
              invocation ->
                  Files.createTempFile(
                      tempDir,
                      invocation.getArgument(0, String.class),
                      invocation.getArgument(1, String.class)))
          .when(fileSystem)
          .createTempFile(anyString(), anyString());
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    var installer =
        new CompiledBinaryInstaller(
            runner,
            new FakeDownloadClient(
                Map.of(directUri(), artifact, signatureUri, "signature".getBytes())),
            fileSystem);
    var module = signedModule(installPath, signatureUri, "A".repeat(40));

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("allowed signer");
    assertThat(installPath).doesNotExist();
    assertThat(tempDir).isEmptyDirectory();
  }

  @Test
  void install_whenModeStagingFails_preservesExistingBinary() throws Exception {
    Path installPath = existingBinary();
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doThrow(new IOException("mode failure")).when(fileSystem).setMode(any(Path.class), anyString());
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(),
            new FakeDownloadClient(Map.of(directUri(), "new".getBytes())),
            fileSystem);

    StepResult result =
        installer.install(module(directUri(), installPath, sha256("new".getBytes()), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(stagingPaths()).isEmpty();
  }

  @Test
  void install_whenFilesystemThrowsRuntime_returnsFailureAndRestoresDestination() throws Exception {
    Path installPath = existingBinary();
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doThrow(new UnsupportedOperationException("unsupported mode"))
        .when(fileSystem)
        .setMode(any(Path.class), anyString());
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(),
            new FakeDownloadClient(Map.of(directUri(), "new".getBytes())),
            fileSystem);

    StepResult result =
        installer.install(module(directUri(), installPath, sha256("new".getBytes()), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("unsupported mode");
    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(stagingPaths()).isEmpty();
  }

  @Test
  void install_whenSymlinkPreparationFails_preservesExistingBinary() throws Exception {
    Path installPath = existingBinary();
    Path symlink = tempDir.resolve("rg-link");
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doThrow(new IOException("link failure"))
        .when(fileSystem)
        .createSymlink(any(Path.class), any(Path.class));
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(),
            new FakeDownloadClient(Map.of(directUri(), "new".getBytes())),
            fileSystem);

    StepResult result =
        installer.install(module(directUri(), installPath, sha256("new".getBytes()), symlink));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(stagingPaths()).isEmpty();
  }

  @Test
  void install_whenAtomicReplacementFails_preservesExistingBinary() throws Exception {
    Path installPath = existingBinary();
    var fileSystem = spy(new DefaultBinaryFileSystem());
    doThrow(new IOException("rename failure"))
        .when(fileSystem)
        .atomicMoveReplace(any(Path.class), any(Path.class));
    var installer =
        new CompiledBinaryInstaller(
            new NoopRunner(),
            new FakeDownloadClient(Map.of(directUri(), "new".getBytes())),
            fileSystem);

    StepResult result =
        installer.install(module(directUri(), installPath, sha256("new".getBytes()), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(Files.readString(installPath)).isEqualTo("old");
    assertThat(stagingPaths()).isEmpty();
  }

  @Test
  void install_whenDelegateReportsSuccessWithoutDestination_failsClosed() {
    Path installPath = userLocalPath("delegated-rg");
    var delegate =
        new DelegatingBinaryInstaller(new NoopRunner(), ignored -> Path.of("/usr/bin/binstaller"));
    var installer =
        new CompiledBinaryInstaller(new NoopRunner(), mock(BinaryFileSystem.class), delegate);

    StepResult result = installer.install(module(directUri(), installPath, "a".repeat(64), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("canonical apps binary");
  }

  @Test
  void install_whenDelegatedRollbackFails_preservesPrimaryMessageAndExitCode() throws Exception {
    Path canonical =
        Path.of(System.getProperty("user.home"), ".apps", "ripgrep", "bin", "rg")
            .toAbsolutePath()
            .normalize();
    BinaryFileSystem fileSystem = mock(BinaryFileSystem.class);
    when(fileSystem.resolvePhysicalEntry(any(Path.class)))
        .thenAnswer(invocation -> invocation.getArgument(0, Path.class));
    when(fileSystem.createTempDirectory(any(Path.class), anyString()))
        .thenReturn(tempDir.resolve("backups"));
    doThrow(new IOException("restore failed")).when(fileSystem).deleteTreeIfExists(canonical);
    ShellRunner delegateRunner =
        (command, environment, timeout) ->
            new ProcessResult(42, "", "checksum mismatch", Duration.ZERO);
    var delegate =
        new DelegatingBinaryInstaller(delegateRunner, ignored -> Path.of("/usr/bin/binstaller"));
    var installer = new CompiledBinaryInstaller(new NoopRunner(), fileSystem, delegate);

    StepResult result = installer.install(module(directUri(), canonical, "a".repeat(64), null));

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    StepResult.Failure failure = (StepResult.Failure) result;
    assertThat(failure.errorMessage())
        .contains("checksum mismatch")
        .contains("rollback was incomplete")
        .contains("restore failed");
    assertThat(failure.exitCode()).isEqualTo(42);
  }

  @Test
  void install_whenDelegationWouldHidePrivilegedSymlink_refusesBeforeBinstaller() {
    URI uri = URI.create("https://example.test/rg.zip");
    ShellRunner runner = mock(ShellRunner.class);
    var delegate = new DelegatingBinaryInstaller(runner, ignored -> Path.of("/usr/bin/binstaller"));
    var installer =
        new CompiledBinaryInstaller(new NoopRunner(), mock(BinaryFileSystem.class), delegate);
    var module =
        new CompiledBinaryModule(
            new ModuleName("ripgrep"),
            "rg",
            new BinaryUrl(uri),
            Optional.of(new Checksum("sha256", "a".repeat(64))),
            Optional.empty(),
            Optional.empty(),
            Path.of("/usr/local/bin/rg"),
            Optional.of("rg"),
            0,
            Optional.of("0750"),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.empty());

    StepResult result = installer.install(module);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("privileged symlinks");
    verify(runner, never()).run(any(), any(), any());
  }

  private CompiledBinaryInstaller installer(Map<URI, byte[]> downloads) {
    return installer(new NoopRunner(), downloads);
  }

  private CompiledBinaryInstaller installer(ShellRunner shellRunner, Map<URI, byte[]> downloads) {
    return new CompiledBinaryInstaller(
        shellRunner, new FakeDownloadClient(downloads), new DefaultBinaryFileSystem());
  }

  private Path existingBinary() throws IOException {
    Path installPath = tempDir.resolve("rg");
    Files.writeString(installPath, "old");
    return installPath;
  }

  private List<Path> stagingPaths() throws IOException {
    try (var paths = Files.list(tempDir)) {
      return paths.filter(path -> path.getFileName().toString().startsWith(".sysboot-")).toList();
    }
  }

  private CompiledBinaryModule module(
      URI uri, Path installPath, String checksum, Path symlinkPath) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(uri),
        Optional.of(new Checksum("sha256", checksum)),
        Optional.empty(),
        Optional.empty(),
        installPath,
        CompiledBinaryArtifactFormat.isArchive(uri) ? Optional.of("rg") : Optional.empty(),
        0,
        Optional.of("0750"),
        Optional.ofNullable(symlinkPath),
        false,
        Optional.empty(),
        Optional.empty());
  }

  private CompiledBinaryModule signedModule(
      Path installPath, URI signatureUri, String signerFingerprint) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(directUri()),
        Optional.empty(),
        Optional.empty(),
        Optional.of(new BinaryUrl(signatureUri)),
        installPath,
        Optional.empty(),
        0,
        Optional.of("0750"),
        Optional.empty(),
        false,
        Optional.empty(),
        Optional.empty(),
        Optional.of(signerFingerprint));
  }

  private CompiledBinaryModule signedArchiveModule(
      Path installPath, URI signatureUri, Optional<URI> checksumUri) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(archiveUri()),
        Optional.empty(),
        checksumUri.map(BinaryUrl::new),
        Optional.of(new BinaryUrl(signatureUri)),
        installPath,
        Optional.of("rg"),
        0,
        Optional.of("0750"),
        Optional.empty(),
        false,
        Optional.empty(),
        Optional.empty(),
        Optional.of("A".repeat(40)));
  }

  private DefaultBinaryFileSystem privilegedFileSystem() throws IOException {
    var fileSystem = spy(new DefaultBinaryFileSystem());
    org.mockito.Mockito.doReturn(false).when(fileSystem).isWritable(tempDir);
    org.mockito.Mockito.doReturn(true).when(fileSystem).isRootOwned(tempDir);
    org.mockito.Mockito.doReturn(true).when(fileSystem).isSecurePrivilegedDirectory(tempDir);
    return fileSystem;
  }

  private ShellRunner validSignatureRunner() {
    return new FixedRunner(
        new ProcessResult(
            0, "[GNUPG:] VALIDSIG " + "A".repeat(40) + " 0 0 0 4 0 1 10 00\n", "", Duration.ZERO));
  }

  private BinaryDownloadClient mutatingChecksumClient(
      byte[] trustedArchive,
      byte[] attackerArchive,
      URI signatureUri,
      URI checksumUri,
      AtomicReference<Path> artifactPath) {
    return new BinaryDownloadClient() {
      @Override
      public void downloadToFile(URI url, Path destination) throws IOException {
        if (url.equals(archiveUri())) {
          artifactPath.set(destination);
          Files.write(destination, trustedArchive);
        } else if (url.equals(signatureUri)) {
          Files.writeString(destination, "signature");
        }
      }

      @Override
      public String downloadText(URI url) throws IOException {
        assertThat(url).isEqualTo(checksumUri);
        Files.write(artifactPath.get(), attackerArchive);
        try {
          return sha256(attackerArchive);
        } catch (Exception e) {
          throw new IOException(e);
        }
      }
    };
  }

  private byte[] tarGz(String entryName, byte[] body) throws IOException {
    var output = new ByteArrayOutputStream();
    try (var gzip = new GzipCompressorOutputStream(output);
        var tar = new TarArchiveOutputStream(gzip)) {
      var entry = new TarArchiveEntry(entryName);
      entry.setSize(body.length);
      tar.putArchiveEntry(entry);
      tar.write(body);
      tar.closeArchiveEntry();
    }
    return output.toByteArray();
  }

  private static URI archiveUri() {
    return URI.create("https://example.test/rg.tar.gz");
  }

  private static URI directUri() {
    return URI.create("https://example.test/rg");
  }

  private static Path userLocalPath(String name) {
    return Path.of(System.getProperty("user.home"), ".local", "bin", name)
        .toAbsolutePath()
        .normalize();
  }

  private static String sha256(byte[] body) throws Exception {
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
    return HexFormat.of().formatHex(digest);
  }

  private record FakeDownloadClient(Map<URI, byte[]> downloads) implements BinaryDownloadClient {
    @Override
    public void downloadToFile(URI url, Path destination) throws IOException {
      Files.write(destination, downloads.get(url));
    }

    @Override
    public String downloadText(URI url) {
      throw new UnsupportedOperationException("not used");
    }
  }

  private static final class SwappingArchivePublisher implements PrivilegedArtifactPublisher {
    private final Path rootArchiveStage;
    private final byte[] attackerArchive;
    private int calls;
    private byte[] binaryBytes;

    private SwappingArchivePublisher(Path rootArchiveStage, byte[] attackerArchive) {
      this.rootArchiveStage = rootArchiveStage;
      this.attackerArchive = attackerArchive.clone();
    }

    @Override
    public ProcessResult publish(
        Path source, Path destination, String mode, dev.sysboot.core.Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        dev.sysboot.core.Sha256Digest expected,
        StagedConsumer consumer)
        throws IOException {
      calls++;
      if (calls == 1) {
        assertThat(ArtifactDigests.sha256(rootArchiveStage)).isEqualTo(expected);
        Files.write(source, attackerArchive);
        return consumer.consume(rootArchiveStage);
      }
      binaryBytes = Files.readAllBytes(source);
      assertThat(ArtifactDigests.sha256(source)).isEqualTo(expected);
      return consumer.consume(source);
    }

    @Override
    public ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) throws IOException {
      calls++;
      Files.write(source, attackerArchive);
      return consumer.consume(rootArchiveStage);
    }
  }

  private static final class ImmutableStagePublisher implements PrivilegedArtifactPublisher {
    private final Path stage;
    private int calls;
    private byte[] binaryBytes;

    private ImmutableStagePublisher(Path stage) {
      this.stage = stage;
    }

    @Override
    public ProcessResult publish(
        Path source, Path destination, String mode, dev.sysboot.core.Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        dev.sysboot.core.Sha256Digest expected,
        StagedConsumer consumer)
        throws IOException {
      calls++;
      if (calls == 1) {
        Files.copy(source, stage, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertThat(ArtifactDigests.sha256(stage)).isEqualTo(expected);
        return consumer.consume(stage);
      }
      assertThat(ArtifactDigests.sha256(source)).isEqualTo(expected);
      binaryBytes = Files.readAllBytes(source);
      return consumer.consume(source);
    }

    @Override
    public ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) throws IOException {
      Files.copy(source, stage, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      return consumer.consume(stage);
    }
  }

  private static final class SuppressedFailurePublisher implements PrivilegedArtifactPublisher {

    @Override
    public ProcessResult publish(
        Path source, Path destination, String mode, dev.sysboot.core.Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        dev.sysboot.core.Sha256Digest expected,
        StagedConsumer consumer) {
      var failure = new ShellExecutionException("primary verification failure");
      failure.addSuppressed(new IOException("root stage cleanup failed"));
      throw failure;
    }

    @Override
    public ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class OuterCleanupFailingPublisher implements PrivilegedArtifactPublisher {

    private final Path stage;

    private OuterCleanupFailingPublisher(Path stage) {
      this.stage = stage;
    }

    @Override
    public ProcessResult publish(
        Path source, Path destination, String mode, dev.sysboot.core.Sha256Digest expected) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ProcessResult consumeVerified(
        Path source,
        Path stagingAnchor,
        String mode,
        dev.sysboot.core.Sha256Digest expected,
        StagedConsumer consumer)
        throws IOException {
      Files.copy(source, stage, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      ProcessResult consumed = consumer.consume(stage);
      assertThat(consumed.isSuccess()).isTrue();
      return new ProcessResult(
          7,
          "",
          "artifact verification failed"
              + System.lineSeparator()
              + "Additionally failed to remove root-owned artifact stage",
          Duration.ZERO);
    }

    @Override
    public ProcessResult consume(
        Path source, Path stagingAnchor, String mode, StagedConsumer consumer) {
      throw new UnsupportedOperationException();
    }
  }

  private record NoopRunner() implements ShellRunner {
    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      return new ProcessResult(0, "", "", Duration.ZERO);
    }
  }

  private record FixedRunner(ProcessResult result) implements ShellRunner {
    @Override
    public ProcessResult run(List<String> command, Map<String, String> env, Duration timeout) {
      return result;
    }
  }
}
