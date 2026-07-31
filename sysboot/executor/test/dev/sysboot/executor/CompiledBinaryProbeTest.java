package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.BinaryUrl;
import dev.sysboot.core.Checksum;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ModuleItem;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.SkipDecision;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompiledBinaryProbeTest {

  @TempDir Path tempDir;

  @Test
  void supports_onlyCompiledBinaryType() {
    var probe = new CompiledBinaryProbe(Optional.empty(), Optional.empty());

    assertThat(probe.supports(ItemType.COMPILED_BINARY)).isTrue();
    assertThat(probe.supports(ItemType.PACKAGE)).isFalse();
  }

  @Test
  void probe_whenBinaryDoesNotExist_returnsNotInstalled() {
    var probe = new CompiledBinaryProbe(Optional.empty(), Optional.empty());

    assertThat(probe.probe(tempDir.resolve("nonexistent").toString()))
        .isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @Test
  void probe_whenConfigurationIsUnavailable_neverExecutesPreseededBinary() throws IOException {
    Path marker = tempDir.resolve("executed");
    Path binary = executable("preseeded", marker, "1.2.3");
    var probe = new CompiledBinaryProbe(Optional.empty(), Optional.empty());

    InstallationStatus status = probe.probe(binary.toString());

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
    assertThat(marker).doesNotExist();
  }

  @Test
  void probe_whenFinalBytesMatchConfiguredChecksum_mayExecuteVersionAfterVerification()
      throws Exception {
    Path marker = tempDir.resolve("executed");
    Path binary = executable("trusted", marker, "1.2.3");
    CompiledBinaryModule module =
        module(binary, sha256(binary), Optional.empty(), Optional.of("1.2"));

    InstallationStatus status =
        new CompiledBinaryProbe(Optional.empty(), Optional.empty()).probe(item(module));

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
    assertThat(((InstallationStatus.InstalledByProbe) status).detectedVersion()).isEqualTo("1.2.3");
    assertThat(marker).exists();
  }

  @Test
  void probe_whenFinalBytesDoNotMatch_neverExecutesPreseededBinary() throws Exception {
    Path marker = tempDir.resolve("executed");
    Path binary = executable("preseeded", marker, "1.2.3");
    CompiledBinaryModule module =
        module(binary, "0".repeat(64), Optional.empty(), Optional.of("1.2"));

    InstallationStatus status =
        new CompiledBinaryProbe(Optional.empty(), Optional.empty()).probe(item(module));

    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
    assertThat(marker).doesNotExist();
  }

  @Test
  void probe_whenChecksumCoversArchive_neverExecutesPreseededInstallPath() throws Exception {
    Path marker = tempDir.resolve("executed");
    Path binary = executable("preseeded", marker, "1.2.3");
    CompiledBinaryModule module =
        module(binary, sha256(binary), Optional.of("bin/tool"), Optional.of("1.2"));

    InstallationStatus status =
        new CompiledBinaryProbe(Optional.empty(), Optional.empty()).probe(item(module));

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
    assertThat(marker).doesNotExist();
  }

  @Test
  void probe_whenTrustRequiresRemoteChecksumOrSigner_neverExecutesPreseededInstallPath()
      throws IOException {
    Path marker = tempDir.resolve("executed");
    Path binary = executable("preseeded", marker, "1.2.3");
    CompiledBinaryModule module =
        new CompiledBinaryModule(
            new ModuleName("tool"),
            "tool",
            new BinaryUrl(URI.create("https://example.test/tool")),
            Optional.empty(),
            Optional.of(new BinaryUrl(URI.create("https://example.test/tool.sha256"))),
            Optional.of(new BinaryUrl(URI.create("https://example.test/tool.asc"))),
            binary,
            Optional.empty(),
            0,
            Optional.of("0755"),
            Optional.empty(),
            false,
            Optional.empty(),
            Optional.of("1.2"),
            Optional.of("A".repeat(40)));

    InstallationStatus status =
        new CompiledBinaryProbe(Optional.empty(), Optional.empty()).probe(item(module));

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
    assertThat(marker).doesNotExist();
  }

  @Test
  void skipDecisions_withoutUsableState_neverTrustOrExecuteUnverifiedPreseededBytes()
      throws Exception {
    Path marker = tempDir.resolve("executed");
    Path binary = executable("preseeded", marker, "9.9.9");
    CompiledBinaryModule module =
        module(binary, "0".repeat(64), Optional.empty(), Optional.of("9.9"));
    ModuleItem item = item(module);
    var probes =
        new InstalledProbeRegistry(
            List.of(new CompiledBinaryProbe(Optional.empty(), Optional.empty())));

    for (RunStateMode mode : List.of(RunStateMode.SKIP_RECORDED, RunStateMode.LIVE_REPROBE)) {
      SkipDecision decision = new SkipEvaluator(Optional.empty(), probes, mode).evaluate(item);

      assertThat(decision).isInstanceOf(SkipDecision.Run.class);
      assertThat(marker).doesNotExist();
    }
  }

  private ModuleItem item(CompiledBinaryModule module) {
    return ModuleItem.configuredModuleItem(
        module, module.installPath().toString(), module.binaryName(), ItemType.COMPILED_BINARY);
  }

  private CompiledBinaryModule module(
      Path installPath,
      String checksum,
      Optional<String> archivePath,
      Optional<String> expectedVersion) {
    URI uri =
        URI.create(
            archivePath.isPresent()
                ? "https://example.test/tool.tar.gz"
                : "https://example.test/tool");
    return new CompiledBinaryModule(
        new ModuleName("tool"),
        "tool",
        new BinaryUrl(uri),
        Optional.of(new Checksum("sha256", checksum)),
        Optional.empty(),
        Optional.empty(),
        installPath,
        archivePath,
        0,
        Optional.of("0755"),
        Optional.empty(),
        false,
        Optional.empty(),
        expectedVersion);
  }

  private Path executable(String name, Path marker, String version) throws IOException {
    Path binary = tempDir.resolve(name);
    Files.writeString(binary, "#!/bin/sh\ntouch '" + marker + "'\necho '" + version + "'\n");
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));
    return binary;
  }

  private String sha256(Path path) throws IOException, NoSuchAlgorithmException {
    return BinaryDigest.hex(new DefaultBinaryFileSystem(), path, "SHA-256", 1024 * 1024);
  }
}
