package dev.sysboot.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SupplyChainOperandPolicyTest {

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/tmp/tool.rpm",
        "./tool.deb",
        "../tool.pkg.tar.zst",
        "https://example.test/tool.rpm",
        "file:/tmp/tool.deb",
        "tool.whl"
      })
  void systemPackageNamesRejectLocalAndUrlArtifacts(String value) {
    assertThatThrownBy(() -> new PackageName(value))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("registry identifier");
  }

  @Test
  void systemPackageNamesRetainRepositoryQualifiedAndNativeSelectors() {
    assertThat(new PackageName("extra/bash").value()).isEqualTo("extra/bash");
    assertThat(new PackageName("@development-tools").value()).isEqualTo("@development-tools");
    assertThat(new PackageName("libc6:amd64").value()).isEqualTo("libc6:amd64");
    assertThat(new PackageName("curl=8.14.1-2").value()).isEqualTo("curl=8.14.1-2");
  }

  @Test
  void privilegedManagerActionsRejectHookConfigAndPluginInjection() {
    assertRejectedAction(
        PackageManagerKind.APT, new PackageManagerAction("upgrade", List.of("-o")));
    assertRejectedAction(
        PackageManagerKind.DNF,
        new PackageManagerAction("upgrade", List.of("--setopt=pluginpath=/tmp")));
    assertRejectedAction(
        PackageManagerKind.PACMAN,
        new PackageManagerAction("sync-upgrade", List.of("--hookdir=/tmp")));
    assertRejectedAction(
        PackageManagerKind.ZYPPER, new PackageManagerAction("dup", List.of("--reposd-dir=/tmp")));
  }

  @Test
  void privilegedManagerActionsRetainFixedFlagsOperandsAndRepositoryIds() {
    assertThat(
            module(
                PackageManagerKind.APT,
                new PackageManagerAction("dist-upgrade", List.of("--with-new-pkgs"))))
        .isNotNull();
    assertThat(
            module(
                PackageManagerKind.DNF,
                new PackageManagerAction("swap", List.of("ffmpeg-free", "ffmpeg"))))
        .isNotNull();
    assertThat(
            module(
                PackageManagerKind.ZYPPER,
                new PackageManagerAction("dup-from", List.of("packman"))))
        .isNotNull();
  }

  @Test
  void flatpakRejectsOptionsAndAlternateSources() {
    assertThatThrownBy(
            () ->
                new FlatpakModule(
                    new ModuleName("apps"), "--user", List.of("org.mozilla.firefox"), false))
        .hasMessageContaining("Flatpak remote name");
    for (String app :
        List.of(
            "--from",
            "./firefox.flatpakref",
            "https://example.test/firefox.flatpakref",
            "org.mozilla.firefox/x86_64/stable")) {
      assertThatThrownBy(
              () -> new FlatpakModule(new ModuleName("apps"), "flathub", List.of(app), false))
          .hasMessageContaining("registry app ID");
    }
  }

  @Test
  void toolPackagesUseBackendAwareRegistryIdentifiers() {
    assertToolRejected(ToolPackageBackend.CARGO, "https://example.test/crate");
    assertToolRejected(ToolPackageBackend.PIPX, "project.whl");
    assertToolRejected(ToolPackageBackend.NPM_GLOBAL, "github:user/project");
    assertToolRejected(ToolPackageBackend.GO_INSTALL, "./cmd/tool");

    assertThat(tool(ToolPackageBackend.CARGO, "cargo-edit", Optional.of("0.13.0"))).isNotNull();
    assertThat(tool(ToolPackageBackend.NPM_GLOBAL, "@scope/tool", Optional.of("1.2.3")))
        .isNotNull();
    assertThat(
            tool(
                ToolPackageBackend.GO_INSTALL,
                "golang.org/x/tools/cmd/stringer",
                Optional.of("v0.36.0")))
        .isNotNull();
  }

  @Test
  void toolPackageVersionsRejectOptionsAndAlternateSources() {
    assertThatThrownBy(
            () -> tool(ToolPackageBackend.NPM_GLOBAL, "eslint", Optional.of("file:../eslint")))
        .hasMessageContaining("registry version");
    assertThatThrownBy(() -> tool(ToolPackageBackend.CARGO, "ripgrep", Optional.of("--root")))
        .hasMessageContaining("registry version");
  }

  private void assertRejectedAction(PackageManagerKind kind, PackageManagerAction action) {
    assertThatThrownBy(() -> module(kind, action))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not an allowed fixed option");
  }

  private PackageModule module(PackageManagerKind kind, PackageManagerAction action) {
    return new PackageModule(
        new ModuleName("packages"), kind, List.of(new PackageName("git")), List.of(action), false);
  }

  private void assertToolRejected(ToolPackageBackend backend, String name) {
    assertThatThrownBy(() -> tool(backend, name, Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("registry identifier");
  }

  private ToolPackagesModule tool(
      ToolPackageBackend backend, String name, Optional<String> version) {
    return new ToolPackagesModule(
        new ModuleName("tools"),
        backend,
        List.of(new ToolPackagesModule.ToolPackage(name, version)),
        false);
  }
}
