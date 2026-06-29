package dev.sysboot.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AptRepositorySourceSetup;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.BootstrapPolicy;
import dev.sysboot.core.CompiledBinaryModule;
import dev.sysboot.core.FlatpakModule;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.FlatpakRemoteSourceSetup;
import dev.sysboot.core.HostFacts;
import dev.sysboot.core.HostFactsProvider;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.RpmRepositorySourceSetup;
import dev.sysboot.core.ShellCommandModule;
import dev.sysboot.core.ShellScriptModule;
import dev.sysboot.core.ZypperRepositorySourceSetup;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    assertThat(result.policy()).isEqualTo(BootstrapPolicy.empty());
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
  void load_whenWorkstationProfilePresent_mapsIdentityAndTarget(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: developer-workstation
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan: []
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.profileName().value()).isEqualTo("developer-workstation");
    assertThat(result.target()).isInstanceOf(OsTarget.FedoraTarget.class);
    assertThat(((OsTarget.FedoraTarget) result.target()).release()).isEqualTo("44");
    assertThat(result.phases()).hasSize(1);
    assertThat(result.phases().getFirst().modules()).isEmpty();
  }

  @Test
  void load_whenWorkstationProfilePackagePlansPresent_mapsPackageAndFlatpakModules(
      @TempDir Path tmpDir) throws IOException {
    Path config = writeConfig(tmpDir, workstationProfileWithAllPackageKinds());

    BootstrapConfig result = loader.load(config);

    assertThat(result.phases()).hasSize(1);
    assertThat(result.phases().getFirst().modules()).hasSize(5);
    assertPackageModule(result, 0, "apt-base", PackageManagerKind.APT, "curl", "git");
    assertPackageModule(result, 1, "dnf-base", PackageManagerKind.DNF, "ripgrep");
    assertPackageModule(result, 2, "pacman-base", PackageManagerKind.PACMAN, "fd");
    assertPackageModule(result, 3, "zypper-base", PackageManagerKind.ZYPPER, "htop");
    assertFlatpakModule(
        result, 4, "desktop-apps", "fedora", "org.mozilla.firefox", "com.slack.Slack");
  }

  @Test
  void load_whenWorkstationProfilePackageActionsPresent_mapsActionsBeforePackages(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: action-test
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: dnf-base
                  kind: dnf-packages
                  spec:
                    actions:
                      - check-update
                      - action: swap
                        args: [ffmpeg-free, ffmpeg]
                    packages: [ripgrep]
            """);

    BootstrapConfig result = loader.load(config);

    var module = (PackageModule) result.phases().getFirst().modules().getFirst();
    assertThat(module.actions()).extracting(action -> action.action())
        .containsExactly("check-update", "swap");
    assertThat(module.actions().get(1).args()).containsExactly("ffmpeg-free", "ffmpeg");
    assertThat(module.packages()).extracting(packageName -> packageName.value())
        .containsExactly("ripgrep");
  }

  @Test
  void load_whenWorkstationProfileSourcesPresent_mapsRelevantSourceSetups(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: source-test
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              sources:
                apt:
                  - name: docker-apt
                    spec:
                      source: deb [arch=amd64] https://download.docker.com/linux/debian bookworm stable
                      sourceList: /etc/apt/sources.list.d/docker.list
                      signingKeyUrl: https://download.docker.com/linux/debian/gpg
                      keyring: /etc/apt/keyrings/docker.gpg
                      checksum:
                        algorithm: sha256
                        value: "0000000000000000000000000000000000000000000000000000000000000000"
                dnf:
                  - name: docker-dnf
                    spec:
                      id: docker
                      baseUrl: https://download.docker.com/linux/fedora/$releasever/$basearch/stable
                      repoFile: /etc/yum.repos.d/docker.repo
                      gpgKeyUrl: https://download.docker.com/linux/fedora/gpg
                      gpgCheck: true
                rpm:
                  - name: rpmfusion
                    spec:
                      id: rpmfusion
                      baseUrl: https://download1.rpmfusion.org/free/fedora/releases/$releasever/Everything/$basearch/os/
                      gpgKeyUrl: https://rpmfusion.org/keys?action=AttachFile&do=get&target=RPM-GPG-KEY-rpmfusion-free-fedora
                      gpgCheck: true
                zypper:
                  - name: packman
                    spec:
                      id: packman
                      baseUrl: https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/
                      repoFile: /etc/zypp/repos.d/packman.repo
                      gpgKeyUrl: https://example.com/packman.asc
                flatpak:
                  - name: flathub
                    spec:
                      remote: flathub
                      url: https://flathub.org/repo/flathub.flatpakrepo
                      system: false
              plan:
                - name: apt-base
                  kind: apt-packages
                  spec:
                    packages: [curl]
                - name: dnf-base
                  kind: dnf-packages
                  spec:
                    packages: [ripgrep]
                - name: zypper-base
                  kind: zypper-packages
                  spec:
                    packages: [htop]
                - name: desktop-apps
                  kind: flatpak-packages
                  spec:
                    apps: [org.mozilla.firefox]
            """);

    BootstrapConfig result = loader.load(config);

    assertPackageModule(result, 0, "apt-base", PackageManagerKind.APT, "curl");
    assertPackageModule(result, 1, "dnf-base", PackageManagerKind.DNF, "ripgrep");
    assertPackageModule(result, 2, "zypper-base", PackageManagerKind.ZYPPER, "htop");
    assertFlatpakModule(result, 3, "desktop-apps", "flathub", "org.mozilla.firefox");
    assertThat(result.sourceSetups()).hasSize(5);
    assertAptSource(result, 0);
    assertRpmSource(result, 1, "docker-dnf", "/etc/yum.repos.d/docker.repo");
    assertRpmSource(result, 2, "rpmfusion", "/etc/yum.repos.d/rpmfusion.repo");
    assertZypperSource(result, 3);
    assertFlatpakSource(result, 4);
    assertThat(result.skippedPlanEntries()).isEmpty();
  }

  @Test
  void load_whenWorkstationProfileSourcesIrrelevantToSelectedHost_skipsWithStableReason(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: source-skip-test
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              sources:
                apt:
                  - name: docker-apt
                    spec:
                      source: deb https://download.docker.com/linux/debian bookworm stable
                dnf:
                  - name: docker-dnf
                    spec:
                      id: docker
                      baseUrl: https://download.docker.com/linux/fedora/$releasever/stable
                      gpgKeyUrl: https://download.docker.com/linux/fedora/gpg
                zypper:
                  - name: packman
                    spec:
                      id: packman
                      baseUrl: https://ftp.gwdg.de/pub/linux/misc/packman/suse/openSUSE_Tumbleweed/
                      gpgKeyUrl: https://example.com/packman.asc
                flatpak:
                  - name: flathub
                    spec:
                      remote: flathub
                      url: https://flathub.org/repo/flathub.flatpakrepo
              plan:
                - name: apt-base
                  kind: apt-packages
                  when:
                    distribution: debian
                  spec:
                    packages: [curl]
                - name: dnf-base
                  kind: dnf-packages
                  when:
                    distribution: fedora
                  spec:
                    packages: [ripgrep]
                - name: zypper-base
                  kind: zypper-packages
                  when:
                    distribution: opensuse
                  spec:
                    packages: [htop]
                - name: desktop-apps
                  kind: flatpak-packages
                  when:
                    distribution: debian
                  spec:
                    apps: [org.mozilla.firefox]
            """);
    BootstrapConfig result =
        new YamlConfigLoader(new FakeHostFactsProvider(facts("fedora", "44", ""), Set.of()))
            .load(config);

    assertThat(result.sourceSetups()).hasSize(1);
    assertThat(result.sourceSetups().getFirst().name().value()).isEqualTo("docker-dnf");
    assertThat(result.skippedPlanEntries())
        .filteredOn(entry -> entry.kind().endsWith("-source"))
        .extracting(entry -> entry.name(), entry -> entry.kind(), entry -> entry.reason())
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "docker-apt",
                "apt-source",
                "source section apt is not relevant to selected host package managers"),
            org.assertj.core.groups.Tuple.tuple(
                "packman",
                "zypper-source",
                "source section zypper is not relevant to selected host package managers"),
            org.assertj.core.groups.Tuple.tuple(
                "flathub",
                "flatpak-source",
                "source section flatpak is not relevant to selected host package managers"));
  }

  @Test
  void load_whenWorkstationProfileSourcesInvalid_reportsSectionFieldPaths(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: invalid-source-test
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              sources:
                apt:
                  - name: " "
                    spec:
                      signingKeyUrl: relative/key
                      keyring: relative/keyring.gpg
                      checksum:
                        algorithm: sha1
                        value: nope
                dnf:
                  - name: docker
                    spec:
                      id: " "
                      baseUrl: relative/repo
                      gpgCheck: true
                rpm:
                  - name: rpmfusion
                    spec:
                      baseUrl: relative/repo
                zypper:
                  - name: packman
                    spec:
                      id: packman
                      gpgKeyUrl: relative/key.asc
                flatpak:
                  - name: flathub
                    spec:
                      remote: " "
                      url: relative.flatpakrepo
              plan: []
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.sources.apt[0].name")
        .hasMessageContaining("spec.sources.apt[0].spec.source")
        .hasMessageContaining("spec.sources.apt[0].spec.signingKeyUrl")
        .hasMessageContaining("spec.sources.apt[0].spec.keyring")
        .hasMessageContaining("spec.sources.apt[0].spec.checksum.algorithm")
        .hasMessageContaining("spec.sources.apt[0].spec.checksum.value")
        .hasMessageContaining("spec.sources.dnf[0].spec.id")
        .hasMessageContaining("spec.sources.dnf[0].spec.baseUrl")
        .hasMessageContaining("spec.sources.dnf[0].spec.gpgKeyUrl")
        .hasMessageContaining("spec.sources.rpm[0].spec.id")
        .hasMessageContaining("spec.sources.rpm[0].spec.baseUrl")
        .hasMessageContaining("spec.sources.rpm[0].spec.gpgKeyUrl")
        .hasMessageContaining("spec.sources.zypper[0].spec.baseUrl")
        .hasMessageContaining("spec.sources.zypper[0].spec.gpgKeyUrl")
        .hasMessageContaining("spec.sources.flatpak[0].spec.remote")
        .hasMessageContaining("spec.sources.flatpak[0].spec.url");
  }

  @Test
  void load_whenWorkstationProfileWhenMatchesFakeHostFacts_selectsExpectedEntries(
      @TempDir Path tmpDir) throws IOException {
    assertSelectedModules(tmpDir, facts("debian", "12", "bookworm"), "apt-base");
    assertSelectedModules(tmpDir, facts("ubuntu", "24.04", "noble"), "apt-base");
    assertSelectedModules(tmpDir, facts("fedora", "44", ""), "dnf-base");
    assertSelectedModules(tmpDir, facts("arch", "", ""), "pacman-base");
    assertSelectedModules(tmpDir, facts("opensuse", "15.6", ""), "zypper-base");
  }

  @Test
  void load_whenCommandExistsPresent_usesInjectedHostFactsBoundary(@TempDir Path tmpDir)
      throws IOException {
    var hostFacts = new FakeHostFactsProvider(facts("debian", "12", "bookworm"), Set.of("apt"));
    YamlConfigLoader hostLoader = new YamlConfigLoader(hostFacts);
    Path config = writeConfig(tmpDir, workstationProfileWithCommandConditions());

    BootstrapConfig result = hostLoader.load(config);

    assertThat(result.modules()).extracting(module -> module.name().value())
        .containsExactly("apt-gated");
    assertThat(hostFacts.commandChecks()).containsExactly("apt", "dnf");
  }

  @Test
  void load_whenWorkstationProfileHasNoWhen_keepsPlanEntriesUnchanged(@TempDir Path tmpDir)
      throws IOException {
    var hostFacts = new FakeHostFactsProvider(facts("arch", "", ""), Set.of());
    Path config = writeConfig(tmpDir, workstationProfileWithAllPackageKinds());

    BootstrapConfig result = new YamlConfigLoader(hostFacts).load(config);

    assertThat(result.modules()).extracting(module -> module.name().value())
        .containsExactly("apt-base", "dnf-base", "pacman-base", "zypper-base", "desktop-apps");
  }

  @Test
  void load_whenWorkstationProfileTargetsSupportedDistributions_mapsToOsTargets(
      @TempDir Path tmpDir)
      throws IOException {
    assertThat(loadWorkstationTarget(tmpDir, "fedora", "release: \"41\""))
        .isInstanceOf(OsTarget.FedoraTarget.class);
    assertThat(loadWorkstationTarget(tmpDir, "arch", "release: rolling"))
        .isInstanceOf(OsTarget.ArchTarget.class);
    assertThat(loadWorkstationTarget(tmpDir, "opensuse", "release: \"15.6\""))
        .isInstanceOf(OsTarget.OpenSuseTarget.class);
    assertThat(loadWorkstationTarget(tmpDir, "debian", "release: \"12\""))
        .isInstanceOf(OsTarget.DebianTarget.class);
    assertThat(loadWorkstationTarget(tmpDir, "ubuntu", "release: \"24.04\""))
        .isInstanceOf(OsTarget.DebianTarget.class);
  }

  @Test
  void load_whenTopLevelSchemaUnknown_throwsClearConfigError(@TempDir Path tmpDir)
      throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            name: unknown
            packages:
              - git
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("Unknown config schema")
        .hasMessageContaining("profile/os/jobs/phases/modules")
        .hasMessageContaining("WorkstationProfile");
  }

  @Test
  void load_whenWorkstationProfileHeaderInvalid_reportsApiVersionAndKind(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v2
            kind: Profile
            metadata:
              name: developer-workstation
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan: []
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("apiVersion")
        .hasMessageContaining("initkit.io/v1alpha1")
        .hasMessageContaining("kind")
        .hasMessageContaining("WorkstationProfile");
  }

  @Test
  void load_whenWorkstationProfilePlanNamesInvalid_reportsOffendingEntries(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: developer-workstation
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: " "
                  kind: commands
                - name: base
                  kind: commands
                - name: base
                  kind: commands
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.plan[0].name")
        .hasMessageContaining("must not be blank")
        .hasMessageContaining("spec.plan[2].name")
        .hasMessageContaining("base")
        .hasMessageContaining("spec.plan[1].name");
  }

  @Test
  void load_whenWorkstationProfilePlanKindUnsupported_reportsFieldPath(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: developer-workstation
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: snaps
                  kind: snap-packages
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.plan[0].kind")
        .hasMessageContaining("snap-packages")
        .hasMessageContaining("unsupported plan kind");
  }

  @Test
  void load_whenWorkstationProfilePackageActionUnsupported_reportsEntryNameAndFieldPath(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: developer-workstation
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: dnf-base
                  kind: dnf-packages
                  spec:
                    actions:
                      - action: autoremove
                    packages: [ripgrep]
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.plan[0].spec.actions[0].action")
        .hasMessageContaining("dnf-base")
        .hasMessageContaining("unsupported action 'autoremove'");
  }

  @Test
  void load_whenWorkstationProfileInstallsEmptyOrChecksumsMalformed_reportsFieldPaths(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: developer-workstation
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: base
                  kind: dnf-packages
                  spec:
                    packages: []
                - name: apps
                  kind: flatpak-packages
                  spec:
                    apps: []
                - name: binary
                  kind: binary-downloads
                  spec:
                    checksum:
                      algorithm: sha1
                      value: nope
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.plan[0].spec.packages")
        .hasMessageContaining("at least one item")
        .hasMessageContaining("spec.plan[1].spec.apps")
        .hasMessageContaining("spec.plan[2].spec.checksum.algorithm")
        .hasMessageContaining("sha1")
        .hasMessageContaining("spec.plan[2].spec.checksum.value");
  }

  @Test
  void load_whenWorkstationProfileStatePathEqualsManifest_reportsFieldPath(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: developer-workstation
            spec:
              policy:
                statePath: config.yaml
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan: []
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.policy.statePath")
        .hasMessageContaining("must not equal the manifest path");
  }

  @Test
  void load_whenWorkstationProfilePolicyPresent_mapsPolicyAndPackageDefaults(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: policy-test
            spec:
              policy:
                dryRun: true
                continueOnError: false
                requireSudo: false
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: dnf-default
                  kind: dnf-packages
                  spec:
                    packages: [git]
                - name: dnf-override
                  kind: dnf-packages
                  execution:
                    continueOnError: true
                  spec:
                    packages: [curl]
                - name: desktop-apps
                  kind: flatpak-packages
                  spec:
                    apps: [org.mozilla.firefox]
            """);

    BootstrapConfig result = loader.load(config);

    assertThat(result.policy().dryRunDefault()).contains(true);
    assertThat(result.policy().continueOnErrorDefault()).contains(false);
    assertThat(result.policy().requireSudoDefault()).contains(false);
    var defaultedModule = (PackageModule) result.modules().get(0);
    var overriddenModule = (PackageModule) result.modules().get(1);
    assertThat(defaultedModule.continueOnError()).isFalse();
    assertThat(overriddenModule.continueOnError()).isTrue();
    assertThat(result.modules().get(2)).isInstanceOf(FlatpakModule.class);
  }

  @Test
  void load_whenWorkstationProfileVarsPresent_interpolatesMappedPackageFields(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: interpolation-test
            spec:
              vars:
                packageName: git
                remoteName: fedora
                browserApp: org.mozilla.firefox
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: dnf-base
                  kind: dnf-packages
                  spec:
                    packages:
                      - ${packageName}
                - name: desktop-apps
                  kind: flatpak-packages
                  spec:
                    remote: ${remoteName}
                    apps:
                      - ${browserApp}
            """);

    BootstrapConfig result = loader.load(config);

    assertPackageModule(result, 0, "dnf-base", PackageManagerKind.DNF, "git");
    assertFlatpakModule(result, 1, "desktop-apps", "fedora", "org.mozilla.firefox");
  }

  @Test
  void load_whenWorkstationProfileUnresolvedVariable_reportsFieldPathAndPlanName(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: interpolation-test
            spec:
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan:
                - name: setup
                  kind: commands
                  spec:
                    commands:
                      - "echo ${missing}"
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.plan[0].spec.commands[0]")
        .hasMessageContaining("plan entry 'setup'")
        .hasMessageContaining("${missing}");
  }

  @Test
  void load_whenWorkstationProfileStatePathInterpolatesToManifest_reportsFieldPath(
      @TempDir Path tmpDir) throws IOException {
    Path config =
        writeConfig(
            tmpDir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: interpolation-test
            spec:
              vars:
                manifestFile: config.yaml
              policy:
                statePath: ${manifestFile}
              target:
                os:
                  distribution: fedora
                  release: "44"
              plan: []
            """);

    assertThatThrownBy(() -> loader.load(config))
        .isInstanceOf(ConfigLoadException.class)
        .hasMessageContaining("spec.policy.statePath")
        .hasMessageContaining("must not equal the manifest path");
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

  private OsTarget loadWorkstationTarget(Path dir, String distribution, String releaseYaml)
      throws IOException {
    Path config =
        writeConfig(
            dir,
            """
            apiVersion: initkit.io/v1alpha1
            kind: WorkstationProfile
            metadata:
              name: target-test
            spec:
              target:
                os:
                  distribution: %s
                  %s
              plan: []
            """
                .formatted(distribution, releaseYaml));
    return loader.load(config).target();
  }

  private void assertSelectedModules(Path tmpDir, HostFacts hostFacts, String... names)
      throws IOException {
    Path config = writeConfig(tmpDir, workstationProfileWithWhenConditions());
    BootstrapConfig result = new YamlConfigLoader(new FakeHostFactsProvider(hostFacts, Set.of()))
        .load(config);
    assertThat(result.modules()).extracting(module -> module.name().value()).containsExactly(names);
  }

  private static HostFacts facts(String distribution, String version, String codename) {
    return new HostFacts(
        "linux",
        Optional.ofNullable(distribution).filter(value -> !value.isBlank()),
        Optional.ofNullable(version).filter(value -> !value.isBlank()),
        Optional.ofNullable(codename).filter(value -> !value.isBlank()),
        "amd64");
  }

  private static void assertPackageModule(
      BootstrapConfig result,
      int index,
      String name,
      PackageManagerKind kind,
      String... packages) {
    assertThat(result.phases().getFirst().modules().get(index)).isInstanceOf(PackageModule.class);
    var module = (PackageModule) result.phases().getFirst().modules().get(index);
    assertThat(module.name().value()).isEqualTo(name);
    assertThat(module.packageManager()).isEqualTo(kind);
    assertThat(module.packages()).extracting(pkg -> pkg.value()).containsExactly(packages);
    assertThat(module.continueOnError()).isTrue();
  }

  private static void assertFlatpakModule(
      BootstrapConfig result, int index, String name, String remote, String... appIds) {
    assertThat(result.phases().getFirst().modules().get(index)).isInstanceOf(FlatpakModule.class);
    var module = (FlatpakModule) result.phases().getFirst().modules().get(index);
    assertThat(module.name().value()).isEqualTo(name);
    assertThat(module.remote()).isEqualTo(remote);
    assertThat(module.appIds()).containsExactly(appIds);
  }

  private static void assertAptSource(BootstrapConfig result, int index) {
    assertThat(result.sourceSetups().get(index)).isInstanceOf(AptRepositorySourceSetup.class);
    var source = (AptRepositorySourceSetup) result.sourceSetups().get(index);
    assertThat(source.name().value()).isEqualTo("docker-apt");
    assertThat(source.sourceListPath().toString()).isEqualTo("/etc/apt/sources.list.d/docker.list");
    assertThat(source.signingKeyUrl()).hasValueSatisfying(url -> assertThat(url.toString())
        .isEqualTo("https://download.docker.com/linux/debian/gpg"));
    assertThat(source.keyringPath()).hasValue(Path.of("/etc/apt/keyrings/docker.gpg"));
  }

  private static void assertRpmSource(
      BootstrapConfig result, int index, String name, String repoFile) {
    assertThat(result.sourceSetups().get(index)).isInstanceOf(RpmRepositorySourceSetup.class);
    var source = (RpmRepositorySourceSetup) result.sourceSetups().get(index);
    assertThat(source.name().value()).isEqualTo(name);
    assertThat(source.repositoryId()).isEqualTo(name.replace("-dnf", ""));
    assertThat(source.repoFilePath().toString()).isEqualTo(repoFile);
    assertThat(source.gpgKeyUrl()).isPresent();
    assertThat(source.enabled()).isTrue();
    assertThat(source.gpgCheck()).isTrue();
  }

  private static void assertZypperSource(BootstrapConfig result, int index) {
    assertThat(result.sourceSetups().get(index)).isInstanceOf(ZypperRepositorySourceSetup.class);
    var source = (ZypperRepositorySourceSetup) result.sourceSetups().get(index);
    assertThat(source.name().value()).isEqualTo("packman");
    assertThat(source.repositoryId()).isEqualTo("packman");
    assertThat(source.repoFilePath().toString()).isEqualTo("/etc/zypp/repos.d/packman.repo");
    assertThat(source.gpgKeyUrl()).isPresent();
  }

  private static void assertFlatpakSource(BootstrapConfig result, int index) {
    assertThat(result.sourceSetups().get(index)).isInstanceOf(FlatpakRemoteSourceSetup.class);
    var source = (FlatpakRemoteSourceSetup) result.sourceSetups().get(index);
    assertThat(source.name().value()).isEqualTo("flathub");
    assertThat(source.remote()).isEqualTo("flathub");
    assertThat(source.url().toString()).isEqualTo("https://flathub.org/repo/flathub.flatpakrepo");
    assertThat(source.system()).isFalse();
  }

  private static String workstationProfileWithAllPackageKinds() {
    return """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: package-plan-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: apt-base
              kind: apt-packages
              spec:
                packages: [curl, git]
            - name: dnf-base
              kind: dnf-packages
              spec:
                packages: [ripgrep]
            - name: pacman-base
              kind: pacman-packages
              spec:
                packages: [fd]
            - name: zypper-base
              kind: zypper-packages
              spec:
                packages: [htop]
            - name: desktop-apps
              kind: flatpak-packages
              spec:
                remote: fedora
                apps: [org.mozilla.firefox]
                appIds: [com.slack.Slack]
            """;
  }

  private static String workstationProfileWithWhenConditions() {
    return """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: when-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: apt-base
              kind: apt-packages
              when:
                distribution:
                  oneOf: [debian, ubuntu]
                architecture: amd64
              spec:
                packages: [curl]
            - name: dnf-base
              kind: dnf-packages
              when:
                distribution: fedora
              spec:
                packages: [ripgrep]
            - name: pacman-base
              kind: pacman-packages
              when:
                distributions: [arch]
              spec:
                packages: [fd]
            - name: zypper-base
              kind: zypper-packages
              when:
                oneOf:
                  - distribution: opensuse
                  - distribution: suse
              spec:
                packages: [htop]
        """;
  }

  private static String workstationProfileWithCommandConditions() {
    return """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: command-test
        spec:
          target:
            os:
              distribution: debian
              release: "12"
          plan:
            - name: apt-gated
              kind: apt-packages
              when:
                commandExists: apt
              spec:
                packages: [curl]
            - name: dnf-gated
              kind: dnf-packages
              when:
                commandExists: dnf
              spec:
                packages: [ripgrep]
        """;
  }

  private static final class FakeHostFactsProvider implements HostFactsProvider {
    private final HostFacts facts;
    private final Set<String> commands;
    private final Set<String> commandChecks = new LinkedHashSet<>();

    private FakeHostFactsProvider(HostFacts facts, Set<String> commands) {
      this.facts = facts;
      this.commands = Set.copyOf(commands);
    }

    @Override
    public HostFacts facts() {
      return facts;
    }

    @Override
    public boolean commandExists(String command) {
      commandChecks.add(command);
      return commands.contains(command);
    }

    private List<String> commandChecks() {
      return List.copyOf(commandChecks);
    }
  }
}
