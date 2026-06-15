package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellScriptModule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlConfigLoaderTest {

  private final YamlConfigLoader loader = new YamlConfigLoader();

  @Test
  void load_whenFedoraConfigValid_parsesAllModulesCorrectly(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: fedora-test
            os:
              type: fedora
              release: "41"
            modules:
              - type: packages
                name: cli-tools
                packageManager: dnf
                continueOnError: true
                packages:
                  - git
                  - curl
              - type: flatpak
                name: apps
                remote: flathub
                appIds:
                  - com.spotify.Client
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.profileName().value()).isEqualTo("fedora-test");
    assertThat(result.target()).isInstanceOf(OsTarget.FedoraTarget.class);
    assertThat(((OsTarget.FedoraTarget) result.target()).release()).isEqualTo("41");
    assertThat(result.modules()).hasSize(2);
    assertThat(result.modules().getFirst()).isInstanceOf(PackageModule.class);
    assertThat(result.modules().get(1)).isInstanceOf(FlatpakModule.class);
  }

  @Test
  void load_whenFlatpakRemoteStep_parsesRemoteConfiguration(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: desktop
            os:
              type: fedora
              release: "44"
            jobs:
              - name: desktop
                steps:
                  - type: flatpak-remote
                    name: flathub
                    remote: flathub
                    url: https://flathub.org/repo/flathub.flatpakrepo
                    system: false
            """);

    BootstrapConfig result = loader.load(config);

    var module = (FlatpakRemoteModule) result.phases().getFirst().modules().getFirst();
    assertThat(module.remote()).isEqualTo("flathub");
    assertThat(module.url().toString()).isEqualTo("https://flathub.org/repo/flathub.flatpakrepo");
    assertThat(module.system()).isFalse();
  }

  @Test
  void load_whenAptRepositoryStep_parsesRepositoryConfiguration(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: debian-test
            os:
              type: debian
              release: "12"
            jobs:
              - name: repositories
                steps:
                  - type: apt-repository
                    name: docker
                    source: deb [signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian bookworm stable
                    signingKeyUrl: https://download.docker.com/linux/debian/gpg
            """);

    BootstrapConfig result = loader.load(config);

    var module = (AptRepositoryModule) result.phases().getFirst().modules().getFirst();
    assertThat(module.sourceListPath().toString()).isEqualTo("/etc/apt/sources.list.d/docker.list");
    assertThat(module.signingKeyUrl())
        .hasValueSatisfying(
            uri -> assertThat(uri).hasToString("https://download.docker.com/linux/debian/gpg"));
    assertThat(module.keyringPath()).hasValue(Path.of("/etc/apt/keyrings/docker.gpg"));
  }

  @Test
  void load_whenRpmRepositoryStep_parsesRepositoryConfiguration(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: fedora-test
            os:
              type: fedora
              release: "44"
            jobs:
              - name: repositories
                steps:
                  - type: rpm-repository
                    name: docker
                    baseUrl: https://download.docker.com/linux/fedora/$releasever/$basearch/stable
                    gpgKeyUrl: https://download.docker.com/linux/fedora/gpg
            """);

    BootstrapConfig result = loader.load(config);

    var module = (RpmRepositoryModule) result.phases().getFirst().modules().getFirst();
    assertThat(module.repositoryId()).isEqualTo("docker");
    assertThat(module.repoFilePath().toString()).isEqualTo("/etc/yum.repos.d/docker.repo");
    assertThat(module.gpgKeyUrl())
        .hasValueSatisfying(
            uri -> assertThat(uri).hasToString("https://download.docker.com/linux/fedora/gpg"));
    assertThat(module.enabled()).isTrue();
    assertThat(module.gpgCheck()).isTrue();
  }

  @Test
  void load_whenPacmanRepositoryStep_parsesRepositoryConfiguration(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: arch-test
            os:
              type: arch
            jobs:
              - name: repositories
                steps:
                  - type: pacman-repository
                    name: chaotic-aur
                    server: https://cdn-mirror.chaotic.cx/$repo/$arch
                    sigLevel: Required DatabaseOptional
            """);

    BootstrapConfig result = loader.load(config);

    var module = (PacmanRepositoryModule) result.phases().getFirst().modules().getFirst();
    assertThat(module.repositoryName()).isEqualTo("chaotic-aur");
    assertThat(module.configPath()).isEqualTo(Path.of("/etc/pacman.conf"));
    assertThat(module.sigLevel()).hasValue("Required DatabaseOptional");
    assertThat(module.enabled()).isTrue();
  }

  @Test
  void load_whenArchConfig_parsesArchTarget(@TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: arch-test
            os:
              type: arch
            modules:
              - type: packages
                name: tools
                packageManager: pacman
                continueOnError: true
                packages:
                  - git
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.target()).isInstanceOf(OsTarget.ArchTarget.class);
  }

  @Test
  void load_whenOpenSuseConfig_parsesZypperModules(@TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: suse-test
            os:
              type: opensuse
              release: "15.6"
            modules:
              - type: packages
                name: tools
                packageManager: zypper
                continueOnError: true
                packages:
                  - git
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.target()).isInstanceOf(OsTarget.OpenSuseTarget.class);
    assertThat(result.modules().getFirst()).isInstanceOf(PackageModule.class);
    var pkg = (PackageModule) result.modules().getFirst();
    assertThat(pkg.packageManager()).isEqualTo(PackageManagerKind.ZYPPER);
  }

  @Test
  void load_whenFileDoesNotExist_throwsConfigLoadException() {
    Path nonExistent = Path.of("/tmp/does-not-exist-sysboot-test.yaml");

    assertThatThrownBy(() -> loader.load(nonExistent))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("does not exist");
  }

  @Test
  void load_whenYamlInvalid_throwsConfigLoadException(@TempDir Path tmpDir) throws IOException {
    Path config = writeConfig(tmpDir, "this: is: not: valid: yaml: [[[");

    assertThatThrownBy(() -> loader.load(config)).isInstanceOf(ConfigLoadException.class);
  }

  @Test
  void load_whenProfileFieldMissing_throwsConfigLoadException(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            os:
              type: fedora
              release: "41"
            modules:
              - type: packages
                name: tools
                packageManager: dnf
                packages:
                  - git
            """);

    assertThatThrownBy(() -> loader.load(config)).isInstanceOf(ConfigLoadException.class);
  }

  @Test
  void load_whenModulesEmpty_throwsConfigLoadException(@TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: test
            os:
              type: fedora
              release: "41"
            modules: []
            """);

    assertThatThrownBy(() -> loader.load(config)).isInstanceOf(ConfigLoadException.class);
  }

  @Test
  void load_whenShellScriptModule_parsesCorrectly(@TempDir Path tmpDir) throws IOException {
    Path script = tmpDir.resolve("setup.sh");
    Files.writeString(script, "#!/bin/bash\necho hello");

    Path config =
        writeConfig(
            tmpDir,
            """
            profile: test
            os:
              type: fedora
              release: "41"
            modules:
              - type: shell-script
                name: setup
                script: setup.sh
                args: []
                continueOnError: false
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.modules().getFirst()).isInstanceOf(ShellScriptModule.class);
  }

  @Test
  void load_whenAssertAndManualSteps_parsesCorrectly(@TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: checkpoints
            os:
              type: fedora
              release: "44"
            jobs:
              - name: prerequisites
                steps:
                  - type: assert
                    name: secure-boot-disabled
                    command: "mokutil --sb-state | grep -qi disabled"
                    message: "Disable Secure Boot before continuing."
                  - type: manual
                    name: github-login
                    message: "Run gh auth login, then continue."
                    probeCommand: "gh auth status"
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.phases()).hasSize(1);
    assertThat(result.phases().getFirst().modules().get(0)).isInstanceOf(AssertModule.class);
    var manual = (ManualModule) result.phases().getFirst().modules().get(1);
    assertThat(manual.message()).contains("gh auth login");
    assertThat(manual.probeCommand()).contains("gh auth status");
  }

  @Test
  void load_whenPhasesDeclareRestartPolicies_parsesPhaseGraph(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: phased
            os:
              type: fedora
              release: "41"
            phases:
              - name: shell-foundation
                restartPolicy:
                  type: prompt-logout
                  message: "Log out and back in."
                continueOnModuleError: false
                modules:
                  - type: packages
                    name: shell-tools
                    packageManager: dnf
                    packages:
                      - zsh
              - name: zsh-ecosystem
                dependsOn:
                  - shell-foundation
                restartPolicy:
                  type: requires-new-shell
                  shell: zsh
                modules:
                  - type: packages
                    name: zsh-tools
                    packageManager: dnf
                    packages:
                      - fzf
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.phases()).hasSize(2);
    assertThat(result.phases().getFirst().restartPolicy())
        .isInstanceOf(RestartPolicy.PromptLogout.class);
    assertThat(result.phases().getFirst().continueOnModuleError()).isFalse();
    assertThat(result.phases().get(1).dependsOn())
        .extracting(dep -> dep.value())
        .containsExactly("shell-foundation");
    assertThat(result.phases().get(1).restartPolicy())
        .isInstanceOf(RestartPolicy.RequiresNewShell.class);
  }

  @Test
  void load_whenWorkflowJobsDeclareSteps_parsesPhaseGraph(@TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: workflow
            os:
              type: fedora
              release: "44"
            jobs:
              - name: system-foundation
                continueOnModuleError: false
                steps:
                  - type: packages
                    name: base-tools
                    packageManager: dnf
                    packages:
                      - git
              - name: shell-tools
                dependsOn:
                  - system-foundation
                steps:
                  - type: shell-command
                    name: git-defaults
                    commands:
                      - "git config --global init.defaultBranch main"
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.phases()).hasSize(2);
    assertThat(result.phases().getFirst().modules().getFirst()).isInstanceOf(PackageModule.class);
    assertThat(result.phases().get(1).dependsOn())
        .extracting(dep -> dep.value())
        .containsExactly("system-foundation");
    assertThat(result.phases().get(1).modules().getFirst()).isInstanceOf(ShellCommandModule.class);
  }

  @Test
  void load_whenCompiledBinaryInstallPathUsesTilde_expandsToHome(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: binary-test
            os:
              type: fedora
              release: "41"
            modules:
              - type: compiled-binary
                name: zoxide
                binaryName: zoxide
                url: https://example.com/zoxide.tar.gz
                installPath: ~/.local/bin/zoxide
            """);

    BootstrapConfig result = loader.load(config);

    var module = (CompiledBinaryModule) result.modules().getFirst();
    assertThat(module.installPath().toString()).startsWith(System.getProperty("user.home"));
  }

  @Test
  void load_whenCompiledBinaryChecksumUrlPresent_parsesChecksumUrl(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: binary-test
            os:
              type: fedora
              release: "41"
            modules:
              - type: compiled-binary
                name: zoxide
                binaryName: zoxide
                url: https://example.com/zoxide.tar.gz
                checksumUrl: https://example.com/zoxide.sha256
                installPath: ~/.local/bin/zoxide
            """);

    BootstrapConfig result = loader.load(config);

    var module = (CompiledBinaryModule) result.modules().getFirst();
    assertThat(module.checksumUrl())
        .hasValueSatisfying(url -> assertThat(url.toString()).endsWith(".sha256"));
  }

  @Test
  void load_whenCompiledBinarySignatureUrlPresent_parsesSignatureUrl(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: binary-test
            os:
              type: fedora
              release: "41"
            modules:
              - type: compiled-binary
                name: zoxide
                binaryName: zoxide
                url: https://example.com/zoxide.tar.gz
                signatureUrl: https://example.com/zoxide.tar.gz.asc
                installPath: ~/.local/bin/zoxide
            """);

    BootstrapConfig result = loader.load(config);

    var module = (CompiledBinaryModule) result.modules().getFirst();
    assertThat(module.signatureUrl())
        .hasValueSatisfying(url -> assertThat(url.toString()).endsWith(".asc"));
  }

  @Test
  void load_whenCompiledBinaryInstallPathIsRelative_throwsConfigLoadException(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: binary-test
            os:
              type: fedora
              release: "41"
            modules:
              - type: compiled-binary
                name: zoxide
                binaryName: zoxide
                url: https://example.com/zoxide.tar.gz
                installPath: bin/zoxide
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("installPath")
        .hasMessageContaining("absolute");
  }

  @Test
  void load_whenShellCommandModulePresent_parsesInlineCommands(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            profile: managed-setup
            os:
              type: fedora
              release: "44"
            modules:
              - type: shell-command
                name: setup-commands
                shell: /bin/bash
                commands:
                  - "git config --global init.defaultBranch main"
                  - "cargo-binstall --no-confirm eza bottom"
                continueOnError: true
                probeCommand: "git config --global --get init.defaultBranch | grep -q main"
            """);

    BootstrapConfig result = loader.load(config);

    var module = (ShellCommandModule) result.modules().getFirst();
    assertThat(module.commands()).hasSize(2);
    assertThat(module.continueOnError()).isTrue();
    assertThat(module.probeCommand())
        .hasValueSatisfying(cmd -> assertThat(cmd).contains("git config"));
  }

  private Path writeConfig(Path dir, String content) throws IOException {
    Path file = dir.resolve("config.yaml");
    Files.writeString(file, content);
    return file;
  }
}
