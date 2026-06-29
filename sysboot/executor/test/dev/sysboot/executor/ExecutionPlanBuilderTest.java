package dev.sysboot.executor;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sysboot.config.YamlConfigLoader;
import dev.sysboot.core.AptRepositoryModule;
import dev.sysboot.core.AssertModule;
import dev.sysboot.core.BootstrapConfig;
import dev.sysboot.core.FlatpakRemoteModule;
import dev.sysboot.core.ItemType;
import dev.sysboot.core.ManualModule;
import dev.sysboot.core.ModuleName;
import dev.sysboot.core.OsTarget;
import dev.sysboot.core.PackageManagerExecutor;
import dev.sysboot.core.PackageManagerKind;
import dev.sysboot.core.PackageModule;
import dev.sysboot.core.PackageName;
import dev.sysboot.core.PacmanRepositoryModule;
import dev.sysboot.core.Phase;
import dev.sysboot.core.PhaseName;
import dev.sysboot.core.ProfileName;
import dev.sysboot.core.RestartPolicy;
import dev.sysboot.core.SkippedPlanEntry;
import dev.sysboot.core.RpmRepositoryModule;
import dev.sysboot.core.StepResult;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecutionPlanBuilderTest {

  @Test
  void build_packageModule_includesItemsAndCommandPreviews() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var phase =
        new Phase(
            new PhaseName("base"),
            "",
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git")),
                    true)),
            List.of(),
            new RestartPolicy.None(),
            false);

    ExecutionPlan plan = builder.build(config(List.of(phase)));

    assertThat(plan.profileName()).isEqualTo("test");
    assertThat(plan.phases()).hasSize(1);
    ExecutionPlan.Item item = plan.phases().get(0).modules().get(0).items().get(0);
    assertThat(item.item().key()).isEqualTo("git");
    assertThat(item.commandPreview()).contains(List.of("sudo", "dnf", "install", "-y", "git"));
  }

  @Test
  void build_whenWorkstationProfilePackagePlansLoaded_includesDryRunPlanItems(
      @TempDir Path tmpDir) throws IOException {
    Path configFile = tmpDir.resolve("config.yaml");
    Files.writeString(configFile, workstationProfileWithAllPackageKinds());
    BootstrapConfig config = new YamlConfigLoader().load(configFile);
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(packageExecutors()));

    ExecutionPlan plan = builder.build(config);

    assertThat(plan.profileName()).isEqualTo("package-plan-test");
    assertThat(plan.phases().getFirst().modules()).extracting(ExecutionPlan.Module::name)
        .containsExactly("apt-base", "dnf-base", "pacman-base", "zypper-base", "desktop-apps");
    assertPlanItem(plan, 0, "curl", PackageManagerKind.APT);
    assertPlanItem(plan, 1, "ripgrep", PackageManagerKind.DNF);
    assertPlanItem(plan, 2, "fd", PackageManagerKind.PACMAN);
    assertPlanItem(plan, 3, "htop", PackageManagerKind.ZYPPER);
    assertFlatpakPlanItem(plan, 4, "org.mozilla.firefox");
  }

  @Test
  void build_whenWorkstationProfilePlanEntriesSkipped_includesSkipReasons(
      @TempDir Path tmpDir) throws IOException {
    Path configFile = tmpDir.resolve("config.yaml");
    Files.writeString(configFile, workstationProfileWithSkippedPlan());
    BootstrapConfig config = new YamlConfigLoader().load(configFile);
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(packageExecutors()));

    ExecutionPlan plan = builder.build(config);

    assertThat(plan.phases().getFirst().modules()).extracting(ExecutionPlan.Module::name)
        .containsExactly("selected");
    assertThat(plan.skippedEntries())
        .extracting(SkippedPlanEntry::name, SkippedPlanEntry::kind)
        .containsExactly(org.assertj.core.groups.Tuple.tuple("skipped", "dnf-packages"));
    assertThat(plan.skippedEntries().getFirst().reason())
        .startsWith("when.distribution expected one of [no-such-os] but was");
  }

  @Test
  void build_restartPolicy_mapsPromptLogoutEffect() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var base =
        new Phase(
            new PhaseName("base"),
            "",
            List.of(
                new PackageModule(
                    new ModuleName("base-tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("git")),
                    true)),
            List.of(),
            new RestartPolicy.None(),
            false);
    var shell =
        new Phase(
            new PhaseName("shell"),
            "",
            List.of(
                new PackageModule(
                    new ModuleName("tools"),
                    PackageManagerKind.DNF,
                    List.of(new PackageName("zsh")),
                    true)),
            List.of(new PhaseName("base")),
            new RestartPolicy.PromptLogout("log out"),
            false);

    ExecutionPlan plan = builder.build(config(List.of(base, shell)));

    ExecutionPlan.Phase shellPhase =
        plan.phases().stream()
            .filter(phase -> phase.name().equals("shell"))
            .findFirst()
            .orElseThrow();

    assertThat(shellPhase.dependsOn()).containsExactly("base");
    assertThat(shellPhase.restartEffect()).isEqualTo(ExecutionPlan.RestartEffect.PROMPT_LOGOUT);
  }

  @Test
  void build_checkpointModules_includesAssertAndManualItems() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var phase =
        new Phase(
            new PhaseName("checks"),
            "",
            List.of(
                new AssertModule(
                    new ModuleName("secure-boot"),
                    "mokutil --sb-state",
                    "Secure Boot must be disabled",
                    "/bin/bash",
                    Optional.empty()),
                new ManualModule(
                    new ModuleName("github-login"),
                    "Run gh auth login",
                    Optional.of("gh auth status"))),
            List.of(),
            new RestartPolicy.None(),
            false);

    ExecutionPlan plan = builder.build(config(List.of(phase)));

    assertThat(plan.phases().getFirst().modules())
        .extracting(ExecutionPlan.Module::type)
        .containsExactly("assert", "manual");
    assertThat(plan.phases().getFirst().modules().get(0).items().getFirst().item().itemType())
        .isEqualTo(ItemType.ASSERT);
    assertThat(plan.phases().getFirst().modules().get(1).items().getFirst().item().itemType())
        .isEqualTo(ItemType.MANUAL);
  }

  @Test
  void build_flatpakRemoteModule_includesCommandPreview() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var phase =
        new Phase(
            new PhaseName("desktop"),
            "",
            List.of(
                new FlatpakRemoteModule(
                    new ModuleName("flathub"),
                    "flathub",
                    URI.create("https://flathub.org/repo/flathub.flatpakrepo"),
                    false)),
            List.of(),
            new RestartPolicy.None(),
            false);

    ExecutionPlan plan = builder.build(config(List.of(phase)));

    ExecutionPlan.Item item = plan.phases().getFirst().modules().getFirst().items().getFirst();
    assertThat(item.item().itemType()).isEqualTo(ItemType.FLATPAK_REMOTE);
    assertThat(item.commandPreview())
        .contains(
            List.of(
                "flatpak",
                "--user",
                "remote-add",
                "--if-not-exists",
                "flathub",
                "https://flathub.org/repo/flathub.flatpakrepo"));
  }

  @Test
  void build_aptRepositoryModule_includesCommandPreview() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var phase =
        new Phase(
            new PhaseName("repositories"),
            "",
            List.of(
                new AptRepositoryModule(
                    new ModuleName("docker"),
                    "deb https://download.docker.com/linux/debian bookworm stable",
                    Path.of("/etc/apt/sources.list.d/docker.list"),
                    Optional.empty(),
                    Optional.empty())),
            List.of(),
            new RestartPolicy.None(),
            false);

    ExecutionPlan plan = builder.build(config(List.of(phase)));

    ExecutionPlan.Item item = plan.phases().getFirst().modules().getFirst().items().getFirst();
    assertThat(item.item().itemType()).isEqualTo(ItemType.APT_REPOSITORY);
    assertThat(item.commandPreview().orElseThrow())
        .containsExactly(
            "/bin/bash",
            "-lc",
            "printf %s\\\\n"
                + " 'deb https://download.docker.com/linux/debian bookworm stable' | sudo tee"
                + " '/etc/apt/sources.list.d/docker.list' >/dev/null && sudo apt-get update");
  }

  @Test
  void build_rpmRepositoryModule_includesCommandPreview() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var phase =
        new Phase(
            new PhaseName("repositories"),
            "",
            List.of(
                new RpmRepositoryModule(
                    new ModuleName("docker"),
                    "docker",
                    URI.create("https://download.docker.com/linux/fedora/$releasever/stable"),
                    Path.of("/etc/yum.repos.d/docker.repo"),
                    Optional.empty(),
                    true,
                    false)),
            List.of(),
            new RestartPolicy.None(),
            false);

    ExecutionPlan plan = builder.build(config(List.of(phase)));

    ExecutionPlan.Item item = plan.phases().getFirst().modules().getFirst().items().getFirst();
    assertThat(item.item().itemType()).isEqualTo(ItemType.RPM_REPOSITORY);
    assertThat(item.commandPreview().orElseThrow())
        .containsExactly(
            "/bin/bash",
            "-lc",
            "printf %s '[docker]\n"
                + "name=docker\n"
                + "baseurl=https://download.docker.com/linux/fedora/$releasever/stable\n"
                + "enabled=1\n"
                + "gpgcheck=0\n"
                + "' | sudo tee '/etc/yum.repos.d/docker.repo' >/dev/null && sudo dnf makecache"
                + " --refresh");
  }

  @Test
  void build_pacmanRepositoryModule_includesCommandPreview() {
    var builder = new ExecutionPlanBuilder(new PackageManagerExecutorRegistry(List.of(dnf())));
    var phase =
        new Phase(
            new PhaseName("repositories"),
            "",
            List.of(
                new PacmanRepositoryModule(
                    new ModuleName("chaotic-aur"),
                    "chaotic-aur",
                    URI.create("https://cdn-mirror.chaotic.cx/$repo/$arch"),
                    Path.of("/etc/pacman.conf"),
                    Optional.of("Required DatabaseOptional"),
                    Optional.empty(),
                    true)),
            List.of(),
            new RestartPolicy.None(),
            false);

    ExecutionPlan plan = builder.build(config(List.of(phase)));

    ExecutionPlan.Item item = plan.phases().getFirst().modules().getFirst().items().getFirst();
    assertThat(item.item().itemType()).isEqualTo(ItemType.PACMAN_REPOSITORY);
    assertThat(item.commandPreview().orElseThrow())
        .containsExactly(
            "/bin/bash",
            "-lc",
            "grep -Eq '^\\[chaotic-aur\\]$' '/etc/pacman.conf' || printf %s '\n"
                + "[chaotic-aur]\n"
                + "Server = https://cdn-mirror.chaotic.cx/$repo/$arch\n"
                + "SigLevel = Required DatabaseOptional\n"
                + "' | sudo tee -a '/etc/pacman.conf' >/dev/null; sudo pacman -Sy");
  }

  private static BootstrapConfig config(List<Phase> phases) {
    var builder =
        BootstrapConfig.builder()
            .profileName(new ProfileName("test"))
            .target(new OsTarget.FedoraTarget("44"));
    phases.forEach(builder::addPhase);
    return builder.build();
  }

  private static void assertPlanItem(
      ExecutionPlan plan, int moduleIndex, String key, PackageManagerKind kind) {
    ExecutionPlan.Item item =
        plan.phases().getFirst().modules().get(moduleIndex).items().getFirst();
    assertThat(item.item().key()).isEqualTo(key);
    assertThat(item.item().packageManager()).contains(kind);
    assertThat(item.commandPreview().orElseThrow())
        .containsExactly("sudo", kind.name().toLowerCase(), "install", "-y", key);
  }

  private static void assertFlatpakPlanItem(ExecutionPlan plan, int moduleIndex, String key) {
    ExecutionPlan.Module module = plan.phases().getFirst().modules().get(moduleIndex);
    ExecutionPlan.Item item = module.items().getFirst();
    assertThat(module.type()).isEqualTo("flatpak");
    assertThat(item.item().key()).isEqualTo(key);
    assertThat(item.item().itemType()).isEqualTo(ItemType.FLATPAK);
    assertThat(item.commandPreview().orElseThrow())
        .containsExactly("flatpak", "install", "-y", "fedora", key);
  }

  private static List<PackageManagerExecutor> packageExecutors() {
    return List.of(
        packageExecutor(PackageManagerKind.APT),
        dnf(),
        packageExecutor(PackageManagerKind.PACMAN),
        packageExecutor(PackageManagerKind.ZYPPER));
  }

  private static PackageManagerExecutor packageExecutor(PackageManagerKind kind) {
    return new PackageManagerExecutor() {
      @Override
      public boolean supports(PackageManagerKind candidate) {
        return candidate == kind;
      }

      @Override
      public List<String> installCommand(PackageName packageName) {
        return List.of("sudo", kind.name().toLowerCase(), "install", "-y", packageName.value());
      }

      @Override
      public StepResult install(PackageName packageName) {
        return new StepResult.Success(packageName.value(), Duration.ZERO);
      }
    };
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

  private static String workstationProfileWithSkippedPlan() {
    return """
        apiVersion: initkit.io/v1alpha1
        kind: WorkstationProfile
        metadata:
          name: skipped-plan-test
        spec:
          target:
            os:
              distribution: fedora
              release: "44"
          plan:
            - name: selected
              kind: dnf-packages
              spec:
                packages: [git]
            - name: skipped
              kind: dnf-packages
              when:
                distribution: no-such-os
              spec:
                packages: [curl]
        """;
  }

  private static PackageManagerExecutor dnf() {
    return new PackageManagerExecutor() {
      @Override
      public boolean supports(PackageManagerKind kind) {
        return kind == PackageManagerKind.DNF;
      }

      @Override
      public List<String> installCommand(PackageName packageName) {
        return List.of("sudo", "dnf", "install", "-y", packageName.value());
      }

      @Override
      public StepResult install(PackageName packageName) {
        return new StepResult.Success(packageName.value(), Duration.ZERO);
      }
    };
  }
}
