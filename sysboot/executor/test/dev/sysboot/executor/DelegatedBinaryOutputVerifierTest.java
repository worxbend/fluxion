package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.StepResult;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DelegatedBinaryOutputVerifierTest {

  @TempDir Path tempDir;

  @Test
  void verify_whenPreexistingCanonicalBinaryIsUnchanged_failsClosed() throws Exception {
    CompiledBinaryModule module = module(canonicalPath());
    BinaryFileSystem fileSystem = regularCanonicalFile("unchanged");
    var verifier = new DelegatedBinaryOutputVerifier(fileSystem);
    var before = verifier.snapshot(module).orElseThrow();

    StepResult result = verifier.verify(module, success(), before);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("did not change");
  }

  @Test
  void verify_whenArchiveOutputOnlyChangesMetadata_failsClosed() throws Exception {
    Path canonical = canonicalPath();
    BinaryFileSystem fileSystem = mock(BinaryFileSystem.class);
    when(fileSystem.isSymbolicLink(canonical)).thenReturn(false);
    when(fileSystem.pathEntryExists(canonical)).thenReturn(true);
    when(fileSystem.isRegularFile(canonical)).thenReturn(true);
    when(fileSystem.fileIdentity(canonical)).thenReturn("inode:old-mtime", "inode:new-mtime");
    when(fileSystem.openInput(canonical))
        .thenAnswer(ignored -> new ByteArrayInputStream("unchanged".getBytes()));
    CompiledBinaryModule module = archiveModule(canonical);
    var verifier = new DelegatedBinaryOutputVerifier(fileSystem);
    var before = verifier.snapshot(module).orElseThrow();

    StepResult result = verifier.verify(module, success(), before);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage()).contains("did not change");
  }

  @Test
  void verify_whenDeclaredPathPointsAtDifferentBinary_failsClosed() throws Exception {
    Path installPath = userLocalPath("rg-different");
    Path canonical = canonicalPath();
    BinaryFileSystem fileSystem = mock(BinaryFileSystem.class);
    when(fileSystem.isSymbolicLink(canonical)).thenReturn(false);
    when(fileSystem.pathEntryExists(canonical)).thenReturn(false, true);
    when(fileSystem.isRegularFile(canonical)).thenReturn(true);
    when(fileSystem.fileIdentity(canonical)).thenReturn("new-identity");
    when(fileSystem.openInput(canonical))
        .thenAnswer(ignored -> new ByteArrayInputStream("new".getBytes()));
    when(fileSystem.isSymbolicLink(installPath)).thenReturn(true);
    when(fileSystem.readSymbolicLink(installPath)).thenReturn(tempDir.resolve("different"));
    var verifier = new DelegatedBinaryOutputVerifier(fileSystem);
    var before = verifier.snapshot(module(installPath)).orElseThrow();

    StepResult result = verifier.verify(module(installPath), success(), before);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("installPath is not a symlink to the canonical apps binary");
  }

  @Test
  void verify_whenCanonicalChangedAndDeclaredPathResolvesToIt_acceptsSuccess() throws Exception {
    Path installPath = userLocalPath("rg-linked");
    Path canonical = canonicalPath();
    BinaryFileSystem fileSystem = newCanonicalFile(canonical);
    when(fileSystem.isSymbolicLink(installPath)).thenReturn(true);
    when(fileSystem.readSymbolicLink(installPath)).thenReturn(canonical);
    var verifier = new DelegatedBinaryOutputVerifier(fileSystem);
    var before = verifier.snapshot(module(installPath)).orElseThrow();

    StepResult result = verifier.verify(module(installPath), success(), before);

    assertThat(result).isInstanceOf(StepResult.Success.class);
  }

  @Test
  void verify_whenDirectOutputDoesNotMatchConfiguredDigest_failsClosed() throws Exception {
    Path canonical = canonicalPath();
    BinaryFileSystem fileSystem = newCanonicalFile(canonical);
    CompiledBinaryModule module =
        module(canonical, Optional.of(new Checksum("sha256", "0".repeat(64))));
    var verifier = new DelegatedBinaryOutputVerifier(fileSystem);
    var before = verifier.snapshot(module).orElseThrow();

    StepResult result = verifier.verify(module, success(), before);

    assertThat(result).isInstanceOf(StepResult.Failure.class);
    assertThat(((StepResult.Failure) result).errorMessage())
        .contains("does not match the configured direct-download digest");
  }

  private BinaryFileSystem regularCanonicalFile(String identity) throws Exception {
    Path canonical = canonicalPath();
    BinaryFileSystem fileSystem = mock(BinaryFileSystem.class);
    when(fileSystem.isSymbolicLink(canonical)).thenReturn(false);
    when(fileSystem.pathEntryExists(canonical)).thenReturn(true);
    when(fileSystem.isRegularFile(canonical)).thenReturn(true);
    when(fileSystem.fileIdentity(canonical)).thenReturn(identity);
    when(fileSystem.openInput(canonical))
        .thenAnswer(ignored -> new ByteArrayInputStream("unchanged".getBytes()));
    return fileSystem;
  }

  private BinaryFileSystem newCanonicalFile(Path canonical) throws Exception {
    BinaryFileSystem fileSystem = mock(BinaryFileSystem.class);
    when(fileSystem.isSymbolicLink(canonical)).thenReturn(false);
    when(fileSystem.pathEntryExists(canonical)).thenReturn(false, true);
    when(fileSystem.isRegularFile(canonical)).thenReturn(true);
    when(fileSystem.fileIdentity(canonical)).thenReturn("new-identity");
    when(fileSystem.openInput(canonical))
        .thenAnswer(ignored -> new ByteArrayInputStream("new".getBytes()));
    return fileSystem;
  }

  private Path canonicalPath() {
    return Path.of(System.getProperty("user.home"), ".apps", "ripgrep", "bin", "rg")
        .toAbsolutePath()
        .normalize();
  }

  private Path userLocalPath(String name) {
    return Path.of(System.getProperty("user.home"), ".local", "bin", name)
        .toAbsolutePath()
        .normalize();
  }

  private CompiledBinaryModule module(Path installPath) {
    return module(installPath, Optional.empty());
  }

  private CompiledBinaryModule module(Path installPath, Optional<Checksum> checksum) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(URI.create("https://example.test/rg")),
        checksum,
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
  }

  private CompiledBinaryModule archiveModule(Path installPath) {
    return new CompiledBinaryModule(
        new ModuleName("ripgrep"),
        "rg",
        new BinaryUrl(URI.create("https://example.test/rg.tar.gz")),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        installPath,
        Optional.of("rg"),
        0,
        Optional.of("0750"),
        Optional.empty(),
        false,
        Optional.empty(),
        Optional.empty());
  }

  private StepResult.Success success() {
    return new StepResult.Success("rg", Duration.ZERO);
  }
}
