package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DirectModuleCanonicalKeyTest {

  @Test
  void duplicateRepositoryDestinationsAreRejectedBeforeExecution() {
    var first = repo("/tmp/plugin", "0".repeat(40));
    var second = repo("/tmp/plugin", "1".repeat(40));

    assertThatThrownBy(
            () -> new GitRepoModule(new ModuleName("repos"), List.of(first, second), false))
        .hasMessageContaining("repeat a destination");
  }

  @Test
  void duplicateQualifiedUnitsAndToolPackageNamesAreRejected() {
    assertThatThrownBy(
            () ->
                new SystemdUnitModule(
                    new ModuleName("units"),
                    SystemdScope.USER,
                    List.of(
                        new SystemdUnitModule.SystemdUnit(
                            "demo", false, SystemdState.UNCHANGED, false),
                        new SystemdUnitModule.SystemdUnit(
                            "demo.service", false, SystemdState.STARTED, false)),
                    false))
        .hasMessageContaining("qualified unit");

    assertThatThrownBy(
            () ->
                new ToolPackagesModule(
                    new ModuleName("tools"),
                    ToolPackageBackend.PIPX,
                    List.of(
                        new ToolPackagesModule.ToolPackage("ruff"),
                        new ToolPackagesModule.ToolPackage("ruff", Optional.of("1.0"))),
                    false))
        .hasMessageContaining("repeat a package");
  }

  @Test
  void toolPackageNamesCannotBeInterpretedAsBackendOptions() {
    assertThatThrownBy(() -> new ToolPackagesModule.ToolPackage("--root"))
        .hasMessageContaining("must not be an option");
    assertThatThrownBy(() -> new ToolPackagesModule.ToolPackage("ruff", Optional.of(" \t")))
        .hasMessageContaining("version must not be blank");
  }

  @Test
  void systemSettingKeysAreStableAndGranular() {
    var module =
        new SystemSettingModule(
            new ModuleName("host"),
            Optional.of(false),
            Optional.of(true),
            Optional.of("UTC"),
            Optional.of("devbox"),
            Map.of("LC_TIME", "C", "LANG", "en_US.UTF-8"),
            false);

    assertThat(module.itemKeys())
        .containsExactly(
            "localRtc", "ntp", "timezone", "hostname", "locale:LANG", "locale:LC_TIME");
  }

  @Test
  void gpgKeysRejectDuplicateNormalizedFingerprints() {
    var first =
        new GpgKeyModule.GpgKey("https://example.test/first.asc", Optional.empty(), "a".repeat(40));
    var second =
        new GpgKeyModule.GpgKey(
            "https://example.test/second.asc", Optional.empty(), "A".repeat(40));

    assertThatThrownBy(
            () -> new GpgKeyModule(new ModuleName("keys"), List.of(first, second), false))
        .hasMessageContaining("duplicate canonical key identities");
  }

  @Test
  void fileWritesRejectDuplicateNormalizedDestinations() {
    FileWriteItem first = fileWrite("/tmp/fluxion/config");
    FileWriteItem second = fileWrite("/tmp/fluxion/nested/../config");

    assertThatThrownBy(
            () -> new FileWriteModule(new ModuleName("files"), List.of(first, second), false))
        .hasMessageContaining("duplicate canonical destinations");
  }

  @Test
  void sdkmanRejectsDuplicateNormalizedPackages() {
    var first = new SdkmanPackage("java", Optional.of("25-tem"));
    var second = new SdkmanPackage(" java ", Optional.of(" 25-tem "));

    assertThatThrownBy(
            () -> new SdkmanModule(new ModuleName("sdks"), List.of(first, second), false))
        .hasMessageContaining("duplicate canonical packages");
  }

  private GitRepoModule.GitRepo repo(String destination, String commit) {
    return new GitRepoModule.GitRepo(
        "https://example.test/repo.git",
        destination,
        Optional.of(commit),
        Optional.empty(),
        false,
        GitRepoUpdate.NONE);
  }

  private FileWriteItem fileWrite(String destination) {
    return new FileWriteItem(
        "config",
        Path.of(destination),
        Optional.of("content"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        false);
  }
}
