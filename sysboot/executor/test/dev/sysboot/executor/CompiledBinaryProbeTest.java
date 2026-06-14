package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.core.InstallationStatus;
import dev.sysboot.core.ItemType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompiledBinaryProbeTest {

  @TempDir Path tempDir;

  @Test
  void supports_compiledBinaryType_returnsTrue() {
    var probe = new CompiledBinaryProbe(Optional.empty(), Optional.empty());
    assertThat(probe.supports(ItemType.COMPILED_BINARY)).isTrue();
  }

  @Test
  void supports_packageType_returnsFalse() {
    var probe = new CompiledBinaryProbe(Optional.empty(), Optional.empty());
    assertThat(probe.supports(ItemType.PACKAGE)).isFalse();
  }

  @Test
  void probe_whenBinaryDoesNotExist_returnsNotInstalled() {
    var probe = new CompiledBinaryProbe(Optional.empty(), Optional.empty());
    InstallationStatus status = probe.probe(tempDir.resolve("nonexistent").toString());
    assertThat(status).isInstanceOf(InstallationStatus.NotInstalled.class);
  }

  @Test
  void probe_whenBinaryExistsAndIsExecutable_returnsInstalledByProbe() throws IOException {
    Path binary = tempDir.resolve("mybinary");
    Files.writeString(binary, "#!/bin/sh\necho '1.2.3'");
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwxr-xr-x"));

    var probe = new CompiledBinaryProbe(Optional.of(binary + " --version"), Optional.empty());
    InstallationStatus status = probe.probe(binary.toString());

    assertThat(status).isInstanceOf(InstallationStatus.InstalledByProbe.class);
  }

  @Test
  void probe_whenFileExistsButNotExecutable_returnsUnknown() throws IOException {
    Path binary = tempDir.resolve("locked");
    Files.writeString(binary, "binary");
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rw-r--r--"));

    var probe = new CompiledBinaryProbe(Optional.empty(), Optional.empty());
    InstallationStatus status = probe.probe(binary.toString());

    assertThat(status).isInstanceOf(InstallationStatus.Unknown.class);
  }
}
